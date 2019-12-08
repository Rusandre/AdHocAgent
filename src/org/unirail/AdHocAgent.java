package org.unirail;

import com.sun.mail.imap.IdleManager;
import com.sun.mail.smtp.SMTPTransport;

import javax.mail.*;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.SubjectTerm;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AdHocAgent extends java.security.SecureClassLoader {
	
	private Path tmp;
	
	@Override protected Class<?> findClass( String name ) throws ClassNotFoundException {
		
		try
		{
			final byte[] bytes = Files.readAllBytes( tmp.resolve( name.replace( '.', '/' ) + ".class" ) );
			return defineClass( name, bytes, 0, bytes.length );
		} catch (IOException e)
		{
			e.printStackTrace();
		}
		return null;
	}
	
	private boolean has_prohibited = false;
	
	private void got_prohibited( String where ) {
		has_prohibited = true;
		LOG.warning( where );
	}
	
	private AdHocAgent( String annotations_directory, Path description_path ) {
		try
		{
			tmp = Files.createTempDirectory( "ClientAgent" );
			final String   meta = tmp.resolve( "org/unirail/AdHoc/" ).toString();
			Process        proc = Runtime.getRuntime().exec( new String[]{"javac", "-d", tmp.toString(), "-cp", annotations_directory, "-encoding", "UTF-8", description_path.toString()} );
			BufferedReader br   = new BufferedReader( new InputStreamReader( proc.getErrorStream() ) );
			
			if (proc.waitFor() != 0)
			{
				LOG.warning( br.lines().collect( Collectors.joining( "\n" ) ) );
				exit( "Compilation error", 7 );
			}
			HashSet<String> unique_names = new HashSet<>();
			
			try (Stream<Path> walk = Files.walk( tmp ))
			{
				final int len = tmp.toString().length() + 1;
				List<String> classes = walk.filter( p -> Files.isRegularFile( p ) && !p.toString().startsWith( meta ) )
						                       .map( p -> p.toString().substring( len ).replace( ".class", "" ).replace( "/", "." ).replace( "\\", "." ) ).collect( Collectors.toList() );
				
				
				for (String pack : classes.get( 0 ).split( "\\." ))
					if (is_prohibited( pack )) got_prohibited( "Package < " + classes.get( 0 ) + " > name component < " + pack + " >  is prohibited" );
				
				
				for (String class_name : classes)
				{
					final Class<?> CLASS = loadClass( class_name );
					
					String simpleName = CLASS.getSimpleName();
					
					if(!unique_names.add( class_name  )) got_prohibited( "Сlass < " + simpleName + " > name is not unique" );
					
					if (is_prohibited( simpleName )) got_prohibited( "Сlass < " + simpleName + " > name is prohibited" );
					
					for (Field fld : CLASS.getFields())
						if (is_prohibited( fld.getName() )) got_prohibited( "Сlass < " + CLASS.getSimpleName() + " > field < " + fld.getName() + " > name is prohibited" );
					
					for (Field fld : CLASS.getDeclaredFields())
						if (is_prohibited( fld.getName() )) got_prohibited( "Сlass < " + CLASS.getSimpleName() + " > field < " + fld.getName() + " > name is prohibited" );
				}
			} catch (IOException e)
			{
				e.printStackTrace();
			}
		} catch (Throwable t)
		{
			t.printStackTrace();
		}
	}
	
	public static void main( String[] args ) {
		try
		{
			final Properties props = new Properties();
			{
				final Path start       = start_file_path();
				Path       client_path = start.getParent().resolve( "client.properties" );
				LOG.info( "Trying to use " + client_path );//in the classpath
				if (Files.exists( client_path ))
				{
					LOG.info( "Using " + client_path );
					props.load( Files.newBufferedReader( client_path ) );
				}
				else
				{
					client_path = destination_dir_path.resolve( "client.properties" );
					LOG.info( "Trying to use " + client_path );//in the current work dir
					if (Files.exists( client_path ))
					{
						LOG.info( "Using " + client_path );
						props.load( Files.newBufferedReader( client_path ) );
					}
					else if (start.endsWith( ".jar" ))//inside jar
					{
						LOG.info( "Trying to use client.properties inside " + start );
						final InputStream is = AdHocAgent.class.getResourceAsStream( "/client.properties" );
						if (is == null) exit( "File client.properties inside " + start + "is not found.", 1 );
						LOG.info( "Using client.properties inside " + start );
						props.load( is );
					}
					else exit( "client.properties file is absent", 1 );
				}
			}
			String password = props.getProperty( "mail.password", "" );
			
			switch (args.length)
			{
				case 1://password
					read_description_file_path( args[0] );
					break;
				case 2:
					read_description_file_path( args[0] );
					password = args[1];
			}
			
			if (description_file_path == null) read_description_file_path( props.getProperty( "description.file.path" ) );
			
			if (!Files.exists( description_file_path )) exit( "Description file " + description_file_path + " is not exist.", 1 );
			
			{// =========================     description file local checking
				
				final long descriptor_file_time = description_file_path.toFile().lastModified();
				if (System.currentTimeMillis() < descriptor_file_time) exit( "Current description " + description_file_path + " is up-to-date.", 0 );
				Path annotations_directory = Paths.get( props.getProperty( "annotations.directory" ) );
				
				if (!Files.exists( annotations_directory )) exit( "AdHoc annotations directory " + annotations_directory + " is not exist.", 1 );
				if (!Files.exists( annotations_directory.resolve( "org" ) )) exit( "Root AdHoc annotations directory 'org' is not exist in " + annotations_directory, 1 );
				
				if (new AdHocAgent( annotations_directory.toString(), description_file_path ).has_prohibited)//check names
					exit( "Prohibited names detected", 1 );
				
				final String name = description_file_path.getFileName().toString();
				project = name.substring( 0, name.length() - 5 ) + (is_testing_result ? "!" : ".") + descriptor_file_time;//compose project name
			}
			
			final String my_email = props.getProperty( "mail.box", props.getProperty( "mail.login" ) );
			
			if (is_testing_result || !props.containsKey( "server.tcp" ))//generate and check via IMAP only
			{
				if (password == "") password = PasswordPanel.showDialog( props.getProperty( "mail.login" ) + " password is:" );
				if (password == "") exit( "Please, provide email password via client.properties or commandline.", 1 );
				
				String login = props.getProperty( "mail.login", props.getProperty( "mail.box", "" ) );
				if (login == "") exit( "Provide email login via client.properties or commandline.", 1 );
				final InternetAddress server_mail = new InternetAddress( props.getProperty( "server.mail", "" ).toLowerCase() );
				
				final Session session = Session.getInstance( props, null );
				
				final String protocol = props.getProperty( "mail.imaps.host" ) != null ? "imaps" :
				                        props.getProperty( "mail.imap.host" ) != null ? "imap" :
				                        props.getProperty( "mail.pop3s.host" ) != null ? "pop3s" :
				                        props.getProperty( "mail.pop3.host" ) != null ? "pop3" : null;
				
				final Store store = session.getStore( protocol );
				LOG.info( "Connecting to " + my_email + " ..." );
				store.connect( login, password );
				LOG.info( "Connected" );
				
				
				final Folder inbox = store.getFolder( "INBOX" );
				inbox.open( Folder.READ_WRITE );
				
				final IdleManager idleManager = new IdleManager( session, Executors.newCachedThreadPool() );
				
				inbox.addMessageCountListener( new MessageCountAdapter() {
					public void messagesAdded( MessageCountEvent ev ) {
						try
						{
							process( Arrays.stream( ev.getMessages() )
									         .filter( ( msg ) -> {
										         try { return msg.getSubject().contains( project );} catch (MessagingException e) {throw new RuntimeException( e );}
									         } ) );
							LOG.info( "Waiting for more messages..." );
							idleManager.watch( inbox ); // keep watching for new messages
						} catch (Exception e)
						{
							e.printStackTrace();
						}
					}
				} );
				
				final String msg_subject = "AdHoc project " + project;
				
				if (description_file_path.toFile().canWrite())// send description
				{
					MimeMessage msg = new MimeMessage( session );
					
					msg.setFrom( my_email );
					msg.setRecipients( Message.RecipientType.TO, server_mail.getAddress() );
					msg.setSubject( msg_subject );
					msg.setText( new String( Files.readAllBytes( description_file_path ), StandardCharsets.UTF_8 ), "UTF-8" );
					
					msg.setDisposition( project );
					
					LOG.info( "Sending description " + description_file_path + "  ..." );
					SMTPTransport smtp = (SMTPTransport) session.getTransport( props.getProperty( "mail.smtps.host" ) != null ? "smtps" : "smtp" );
					smtp.connect( login, password );
					smtp.sendMessage( msg, msg.getAllRecipients() );
					smtp.close();
					LOG.info( "Sent OK!" );
					
					description_file_path.toFile().setWritable( false );//this version of the description file is in process mark
				}
				else
					process( Arrays.stream( inbox.search( new SubjectTerm( project ) ) ) );
				
				LOG.info( "Waiting for server reply..." );
				idleManager.watch( inbox );
			}
			else //fast, API generate only
			{
				String project = description_file_path.getFileName().toString();
				project = project.substring( 0, project.length() - 5 );
				
				final Path mail_project_size = Files.copy( description_file_path, Files.createTempDirectory( "ClientAgent" ).resolve( my_email + " " + project + " " + description_file_path.toFile().length() ) );
				
				final Path jar = mail_project_size.getParent().resolve( "jar" );
				
				new ProcessBuilder( "jar", "cfM", "jar", "*" ).directory( jar.getParent().toFile() ).start().waitFor(); //produce JAR
				mail_project_size.toFile().delete();
				
				final String server_tcp = props.getProperty( "server.tcp" );
				
				LOG.info( "Connecting to the " + server_tcp );
				
				if (server_tcp.startsWith( "http://" ))
				{
					// proxy settings https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Proxies
					// uncomment lines to use proxy or pass proxi params via command line
					//System.setProperty( "http.proxyHost", "127.0.0.1" );
					//System.setProperty( "http.proxyPort", "1080" );
					
					final HttpURLConnection http = (HttpURLConnection) new URL( server_tcp ).openConnection();
					http.setDoOutput( true );
					http.addRequestProperty( "User-Agent", "AdHocClientAgent" );
					http.addRequestProperty( "Accept", "*/*" );
					http.setRequestProperty( "Content-Type", "application/octet-stream" );
					
					final OutputStream os = http.getOutputStream();
					
					LOG.info( "Connected OK" );
					
					Files.copy( jar, os );
					os.flush();
					
					jar.toFile().delete();
					jar.getParent().toFile().delete();
					description_file_path.toFile().setWritable( false );//this version of the description file is in process mark
					
					extract( http.getInputStream() );
					os.close();
				}
				else
				{
					final String[]     parts  = server_tcp.split( ":" );
					final Socket       socket = new Socket( parts[0], Integer.parseInt( parts[1] ) );
					final OutputStream os     = socket.getOutputStream();
					
					LOG.info( "Connected OK" );
					
					Files.copy( jar, os );
					os.flush();
					
					jar.toFile().delete();
					jar.getParent().toFile().delete();
					description_file_path.toFile().setWritable( false );//this version of the description file is in process mark
					
					extract( socket.getInputStream() );
					os.close();
				}
			}
			
			
		} catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	
	static void process( Stream<Message> msgs ) throws Exception {
		
		Message msg = msgs.min(
				( m1, m2 ) ->
				{
					try
					{
						m1.setFlag( Flags.Flag.SEEN, true );
						m2.setFlag( Flags.Flag.SEEN, true );
						return m2.getSentDate().compareTo( m1.getSentDate() );
					} catch (MessagingException e)
					{
						throw new RuntimeException( e );
					}
				} ).orElse( null );
		
		if (msg != null)
		{
			String subj = msg.getSubject();
			
			if (subj.equals( "Generating AdHoc for " + project + " problem report" ))
			{
				LOG.warning( subj );
				LOG.warning( msg.getContent().toString() );
			}
			else if (subj.equals( "AdHoc for " + project + " is generated" ))
			{
				Multipart att = (Multipart) msg.getContent();
				extract( att.getBodyPart( 1 ).getInputStream() );//extract generated code from mail attachment
			}
		}
	}
	
	
	static void exit( String banner, int code ) throws Exception {
		if (code == 0)
			LOG.info( banner );
		else
			LOG.warning( banner );
		
		Thread.sleep( 1000 );
		
		LOG.info( "Press ENTER to exit" );
		System.in.read();
		
		System.exit( code );
	}
	
	private static final Path destination_dir_path = FileSystems.getDefault().getPath( "" ).toAbsolutePath();//working/current directory
	
	private static final Logger LOG = Logger.getLogger( "ClientAgent" );
	
	
	private static boolean is_testing_result;
	private static Path    description_file_path = null;
	
	private static void read_description_file_path( String src ) {
		src = src.trim();
		if (is_testing_result = src.endsWith( "!" )) src = src.substring( 0, src.length() - 1 );//testing request
		description_file_path = Paths.get( src );
	}
	
	private static String project = "";
	
	
	private static void extract( InputStream is ) throws IOException {
		try
		{
			JarInputStream jar    = new JarInputStream( is );
			byte[]         buffer = new byte[1024];
			
			for (JarEntry je; (je = jar.getNextJarEntry()) != null; )
			{
				final String name = je.getName();
				
				final File file = destination_dir_path.resolve( name ).toFile();
				
				if (name.endsWith( "/" )) file.mkdirs();
				else
				{
					FileOutputStream out = new FileOutputStream( file );
					for (int len; -1 < (len = jar.read( buffer )); ) out.write( buffer, 0, len );
					
					if (name.equals( "unirail.info" ))
						System.out.println( Files.lines( file.toPath(), StandardCharsets.UTF_8 ).collect( Collectors.joining( System.lineSeparator() ) ) );//print unirail.info
					
					jar.closeEntry();
					out.flush();
					out.close();
				}
			}
			jar.close();
			Path new_description = destination_dir_path.resolve( description_file_path.getFileName() );
			
			if (!Files.exists( new_description )) exit( "unirail.info only", 2 );//probably received unirail.info only
			
			//old description overwriting
			if (description_file_path.toFile().canWrite())//the file may be been edited till was in the process
				exit( "Attention! Please resolve file version conflict. Compare " + description_file_path + " with received " + destination_dir_path.resolve( description_file_path.getFileName() ), 0 );
			else
			{
				description_file_path.toFile().setWritable( true );
				Files.move( new_description, description_file_path, StandardCopyOption.REPLACE_EXISTING );
				description_file_path.toFile().setLastModified( System.currentTimeMillis() + Integer.MAX_VALUE * 100L ); // successfully updated mark
				
				exit( "Please find generated files in " + destination_dir_path, 0 );
			}
		} catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	public static Path start_file_path() throws Exception {
		Class  context = AdHocAgent.class;
		String classFileName;
		{
			final String name = context.getName();
			final int    idx  = name.lastIndexOf( '.' );
			classFileName = (idx == -1 ? name : name.substring( idx + 1 )) + ".class";
		}
		
		final String uri = context.getResource( classFileName ).toString();
		return Paths.get( URLDecoder.decode( uri.startsWith( "jar:file:/" ) ?
		                                     uri.substring( "jar:file:/".length(), uri.indexOf( '!' ) ) :
		                                     uri.substring( "file:/".length() ), Charset.defaultCharset().name() ) );
	}
	
	public static boolean is_prohibited( String name ) {
		if (name.startsWith( "_" ) || name.endsWith( "_" )) return true;
		switch (name)
		{
			case "actual":
			case "alignas":
			case "annotation":
			case "arguments":
			case "array":
			case "as":
			case "asm":
			case "async":
			case "auto":
			case "await":
			case "become":
			case "bool":
			case "box":
			case "by":
			case "cast":
			case "char16_t":
			case "char32_t":
			case "checked":
			case "companion":
			case "const_cast":
			case "constexpr":
			case "constructor":
			case "crate":
			case "crossinline":
			case "data":
			case "debugger":
			case "decimal":
			case "declare":
			case "decltype":
			case "delegate":
			case "delete":
			case "deprecated":
			case "dllexport":
			case "dllimport":
			case "dst":
			case "dyn":
			case "dynamic":
			case "dynamic_cast":
			case "each":
			case "eval":
			case "event":
			case "expect":
			case "explicit":
			case "export":
			case "extern":
			case "external":
			case "field":
			case "file":
			case "fixed":
			case "fn":
			case "foreach":
			case "friend":
			case "from":
			case "fun":
			case "function":
			case "gcnew":
			case "generic":
			case "get":
			case "i128":
			case "i16":
			case "i32":
			case "i64":
			case "i8":
			case "impl":
			case "implicit":
			case "in":
			case "infix":
			case "init":
			case "inline":
			case "inner":
			case "int16_t":
			case "int32_t":
			case "int64_t":
			case "int8_t":
			case "interior":
			case "internal":
			case "is":
			case "lateinit":
			case "let":
			case "literal":
			case "lock":
			case "loop":
			case "macro":
			case "match":
			case "mod":
			case "module":
			case "move":
			case "mut":
			case "mutable":
			case "naked":
			case "namespace":
			case "noexcept":
			case "noinline":
			case "noreturn":
			case "nothrow":
			case "novtable":
			case "null":
			case "nullptr":
			case "number":
			case "object":
			case "only":
			case "open":
			case "operator":
			case "out":
			case "override":
			case "pack":
			case "param":
			case "params":
			case "priv":
			case "property":
			case "ptr":
			case "pub":
			case "readonly":
			case "receiver":
			case "ref":
			case "register":
			case "reified":
			case "reinterpret_":
			case "reinterpret_cast":
			case "require":
			case "safecast":
			case "sbyte":
			case "sealed":
			case "selectany":
			case "Self":
			case "set":
			case "setparam":
			case "signed":
			case "sizeof":
			case "src":
			case "stackalloc":
			case "static_assert":
			case "static_cast":
			case "str":
			case "string":
			case "struct":
			case "suspend":
			case "symbol":
			case "tailrec":
			case "template":
			case "thread":
			case "trait":
			case "type":
			case "typealias":
			case "typedef":
			case "typeid":
			case "typename":
			case "typeof":
			case "u128":
			case "u16":
			case "u32":
			case "u64":
			case "u8":
			case "uint":
			case "uint16_t":
			case "uint32_t":
			case "uint64_t":
			case "ulong":
			case "unchecked":
			case "union":
			case "unsafe":
			case "unsigned":
			case "unsized":
			case "use":
			case "ushort":
			case "using":
			case "uuid":
			case "val":
			case "value":
			case "vararg":
			case "virtual":
			case "wchar_t":
			case "where":
			case "with":
			case "yield":
				return true;
		}
		return false;
	}
}


class PasswordPanel extends JPanel {
	
	private final JPasswordField JFieldPass;
	private       boolean        gainedFocusBefore;
	
	public PasswordPanel() {
		super( new FlowLayout() );
		gainedFocusBefore = false;
		JFieldPass        = new JPasswordField();
		Dimension d = new Dimension();
		d.setSize( 30, 22 );
		JFieldPass.setMinimumSize( d );
		JFieldPass.setColumns( 10 );
		JLabel JLblPass = new JLabel( "Password: " );
		add( JLblPass );
		add( JFieldPass );
	}
	
	public static String showDialog( String title ) {
		final PasswordPanel pPnl = new PasswordPanel();
		JOptionPane         op   = new JOptionPane( pPnl );
		op.setMessageType( JOptionPane.QUESTION_MESSAGE );
		op.setOptionType( JOptionPane.OK_CANCEL_OPTION );
		JDialog dlg = op.createDialog( null, title );
		dlg.addWindowFocusListener( new WindowAdapter() {
			@Override
			public void windowGainedFocus( WindowEvent e ) {
				if (!pPnl.gainedFocusBefore)
				{
					pPnl.gainedFocusBefore = true;
					pPnl.JFieldPass.requestFocusInWindow();
				}
			}
		} );
		
		dlg.setDefaultCloseOperation( WindowConstants.DISPOSE_ON_CLOSE );
		
		dlg.setVisible( true );
		
		Object val = op.getValue();
		return val != null && val.equals( JOptionPane.OK_OPTION ) ? new String( pPnl.JFieldPass.getPassword() ) : null;
	}
}

