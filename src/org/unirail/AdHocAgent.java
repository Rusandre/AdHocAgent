package org.unirail;


import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AdHocAgent extends java.security.SecureClassLoader {
	
	private static Path tmp;
	
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
	
	private boolean is_wrong = false;
	
	private void wrong( String what ) {
		is_wrong = true;
		LOG.warning( what );
	}
	
	
	private AdHocAgent( String sourcepath ) {
		try
		{
			tmp = Files.createTempDirectory( "ClientAgent" );
			
			{
				ProcessBuilder exec = new ProcessBuilder(
						"javac",
						"-d",
						tmp.toString(),
						"-sourcepath",
						sourcepath,
						"-encoding", "UTF-8",
						provided_file_path.toString() );
				exec.redirectErrorStream( true );
				
				final Path err = tmp.resolve( "err.log" );
				final Path out = tmp.resolve( "out.log" );
				
				exec.redirectError( err.toFile() );
				exec.redirectOutput( out.toFile() );
				
				if (exec.start().waitFor() != 0)
				{
					System.out.write( Files.readAllBytes( err ) ); ;
					System.out.write( Files.readAllBytes( out ) ); ;
					exit( "Compilation error", 7 );
				}
				err.toFile().delete();
				out.toFile().delete();
			}
			
			Set<String> unique_names = new HashSet<>();
			
			try (Stream<Path> walk = Files.walk( tmp ))
			{
				final String meta_path = "org" + File.separator + "unirail" + File.separator + "AdHoc";
				final int    tmp_len   = tmp.toString().length() + 1;
				final String skip_meta = tmp.resolve( meta_path ).toString();
				
				final Set<String> classes = walk.filter( p -> Files.isRegularFile( p ) && !p.toString().startsWith( skip_meta ) ) //skip org/unirail/AdHoc/ meta namespace
						                            .map( p -> p.toString().substring( tmp_len ).replace( ".class", "" ).replace( File.separator, "." ) ).collect( Collectors.toSet() );
				
				//checking prohibited part name in namespaces
				classes.stream().map( full_name -> full_name.substring( 0, full_name.lastIndexOf( '.' ) ) ).distinct().forEach(
						Package ->
						{
							for (String str : Package.split( "\\." ))
								if (is_prohibited( str )) wrong( "Package < " + Package + " > part name < " + str + " >  is prohibited" );
						}
				);
				final String description_file_name = provided_file_path.getFileName().toString();
				final String name                  = description_file_name.substring( 0, description_file_name.length() - 5 );//trim .java
				
				final String root_project = classes.stream().filter( c -> c.endsWith( name ) ).sorted( Comparator.comparingInt( String::length ) ).findFirst().get();
				
				for (String full_name : classes)
				{
					final Class<?> CLASS = loadClass( full_name );
					
					String simpleName = CLASS.getSimpleName();
					if (CLASS.isInterface()) continue;//just skip
					
					
					if (!CLASS.isEnum() && CLASS.getInterfaces().length == 0)//for Pack declaration classes only
						if (full_name.startsWith( root_project ))//in root project pack class
						{
							if (!unique_names.add( simpleName )) wrong( "Pack declaration class < " + full_name + " > name is not unique" );//checking unique_names
						}
						else//none project, Lib pack class
						{
							Annotation[] anns = CLASS.getAnnotations();//imported pack class check ID presently
							if (anns.length == 0 || !anns[0].annotationType().getName().equals( "id" )) wrong( "Library Pack declaration class < " + full_name + " > have to have predefined unique id annotation." );
						}
					
					String su = CLASS.getSuperclass().getSimpleName();
					if ((su.equals( "StdProtocol" ) || su.equals( "AdvProtocol" )) && CLASS.getInterfaces().length != 2)
						wrong( "Interface < " + full_name + " > have to have joint two interfaces." );
					
					
					//and all classes check for prohibited names
					if (is_prohibited( simpleName )) wrong( "Сlass < " + full_name + " > name is prohibited" );
					
					for (Field fld : CLASS.getFields())
						if (is_prohibited( fld.getName() )) wrong( "Сlass < " + full_name + " > field < " + fld.getName() + " > name is prohibited" );
					
					for (Field fld : CLASS.getDeclaredFields())
						if (is_prohibited( fld.getName() )) wrong( "Сlass < " + full_name + " > field < " + fld.getName() + " > name is prohibited" );
				}
				//////////////if (is_wrong) exit( "Something wrong detected. Please fix problems and try again.", 1 );
				
				//combine parts if they exists in one file
				
				String description_src = new String( Files.readAllBytes( provided_file_path ), StandardCharsets.UTF_8 );//load description file content
				
				
				boolean       process_imports = false;
				final Matcher imports         = imports_pattern.matcher( description_src );
				while (imports.find() &&
				       !(process_imports = !imports.group( 1 ).replaceAll( "[\\p{javaWhitespace}\\p{javaIdentifierIgnorable}]", "" ).startsWith( "org.unirail.AdHoc" ))
				) {}
				
				if (process_imports)//descriptor file has some external dependencies. let gather all in one file before upload
				{
					List<Path> java_srcs = new ArrayList<>();//list of used in compilation source files
					for (String dir : sourcepath.split( File.pathSeparator ))
					{
						final int len = dir.length() + 1;
						
						Files.walk( Paths.get( dir ) ).forEach( p -> {
							String s = p.toString();
							
							if (Files.isRegularFile( p )
							    && s.endsWith( ".java" )
							    && !s.equals( description_file_name ) //skip descriptor file itself
							    && !(s = s.substring( len )).startsWith( meta_path )//skip meta annotations
							    && classes.contains( s.substring( 0, s.length() - 5 ).replace( File.separator, "." ) ))//used in compilation
								java_srcs.add( p );
							
						} );
					}
					
					int class_decl = get_class_declare( description_src );//class declaration place
					
					description_src = "package " + root_project + ";\n" +
					                  description_src.substring( class_decl ).trim() + "\n";
					
					for (Path path : java_srcs)
					{
						String src = new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 );
						class_decl = get_class_declare( src );
						
						description_src += src.substring( class_decl + "public ".length() ).trim() + "\n";//skip public
					}
				}
				
				File file = tmp.resolve( project ).toFile();//temp name, before know real file length
				
				OutputStreamWriter os = new OutputStreamWriter( new FileOutputStream( file ), StandardCharsets.UTF_8 );
				os.write( description_src );
				os.flush();
				os.close();
				
				Path out = tmp.resolve( file.length() + "%" + project + (is_testing ? "%" : "") );
				
				file.renameTo( out.toFile() );
				
				new ProcessBuilder( "jar", "cfM", "jar", out.getFileName().toString() ).directory( tmp.toFile() ).start().waitFor(); //produce JAR
				
				
			} catch (IOException e)
			{
				e.printStackTrace();
			}
		} catch (Throwable t)
		{
			t.printStackTrace();
		}
	}
	
	static BytesSrc active_src = null;
	
	interface BytesSrc {
		void push_into( OutputStream dst ) throws Exception;
	}
	
	public static void main( String[] args ) {
		try
		{
			{
				final Path self       = self_path();
				Path       props_path = self.getParent().resolve( "AdHocAgent.properties" );
				LOG.info( "Trying to use " + props_path );// next to program binary
				if (Files.exists( props_path ))
				{
					LOG.info( "Using " + props_path );
					props.load( Files.newBufferedReader( props_path ) );
				}
				else
				{
					props_path = dest_dir_path.resolve( "AdHocAgent.properties" );
					LOG.info( "Trying to use " + props_path );//in the current working dir
					if (Files.exists( props_path ))
					{
						LOG.info( "Using " + props_path );
						props.load( Files.newBufferedReader( props_path ) );
					}
					else if (self.endsWith( ".jar" ))//inside jar
					{
						LOG.info( "Trying to use AdHocAgent.properties inside " + self );
						final InputStream is = AdHocAgent.class.getResourceAsStream( "/AdHocAgent.properties" );
						if (is == null) exit( "File AdHocAgent.properties inside " + self + " is not found.", 1 );
						LOG.info( "Using AdHocAgent.properties inside " + self );
						props.load( is );
					}
					else exit( "AdHocAgent.properties file is not found", 1 );
				}
			}
			
			switch (args.length)
			{
				case 0:
					set_provided_file_path( props.getProperty( "description_file_path" ).trim() );
					break;
				case 1:
					set_provided_file_path( args[0] );
			}
			
			
			if (!Files.exists( provided_file_path )) exit( "Description file " + provided_file_path + " is not exist.", 1 );
			
			// =========================     description file checking locally
			
			final File provided_file      = provided_file_path.toFile();
			final long provided_file_time = provided_file.lastModified();
			if (System.currentTimeMillis() < provided_file_time) exit( "Provided file " + provided_file_path + " is up-to-date.", 0 );
			
			
			project = props.getProperty( "login" ).replace( "%", "_" ) + "%" + provided_file_time + "%" + provided_file_path.getFileName();
			final String  server = props.getProperty( "server" );
			final boolean tcp    = !server.startsWith( "http://" );
			
			final BytesSrc request = dst -> {
				final byte bytes[] = project.getBytes( StandardCharsets.UTF_8 );
				
				if (tcp) write_len( bytes.length, dst );
				
				dst.write( Protocol.Request );
				dst.write( bytes );
			};
			
			if (provided_file_path.toFile().canWrite())
			{
				if (provided_file_path.toString().endsWith( ".proto" ))//proto file conversion
				{
					Files.copy( provided_file_path, tmp = Files.createTempDirectory( "ClientAgent" ).resolve( provided_file_path.toFile().length() + "%" + project + (is_testing ? "%" : "") ) );
					
					new ProcessBuilder( "jar", "cfM", "jar", tmp.getFileName().toString() ).directory( tmp.getParent().toFile() ).start().waitFor(); //produce JAR
					tmp = tmp.getParent();
				}
				else new AdHocAgent( props.getProperty( "sourcepath" ) ); //check
				
				
				active_src = dst -> {
					Path src = tmp.resolve( "jar" );
					
					if (tcp) write_len( (int) src.toFile().length(), dst );
					
					dst.write( Protocol.File );
					Files.copy( src, dst );
					provided_file_path.toFile().setWritable( false );//this version of the description file is in process mark
				};
			}
			else active_src = request;//file was sent, just query result
			
			
			LOG.info( "Connecting to the " + server );
			
			if (tcp)
				for (; ; )
				{
					final String[]     parts  = server.split( ":" );
					final Socket       socket = new Socket( parts[0], Integer.parseInt( parts[1] ) );
					final OutputStream os     = socket.getOutputStream();
					
					LOG.info( "Connected OK" );
					
					active_src.push_into( os );
					os.flush();
					
					receive( socket.getInputStream() );
					os.close();
					
					if (0 < wait_sec)
					{
						System.out.println( "Will request result in " + wait_sec + " seconds" );
						Thread.sleep( wait_sec * 1000 );
					}
					wait_sec   = 0;
					active_src = request;
				}
			else
				for (; ; )
				{
					// proxy settings https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Proxies
					// uncomment lines to use proxy or pass proxi params via command line
					//System.setProperty( "http.proxyHost", "127.0.0.1" );
					//System.setProperty( "http.proxyPort", "1080" );
					
					final HttpURLConnection http = (HttpURLConnection) new URL( server ).openConnection();
					http.setDoOutput( true );
					http.addRequestProperty( "User-Agent", "AdHocAgent" );
					http.addRequestProperty( "Accept", "*/*" );
					http.setRequestProperty( "Content-Type", "application/octet-stream" );
					
					final OutputStream os = http.getOutputStream();
					
					LOG.info( "Connected OK" );
					
					active_src.push_into( os );
					os.flush();
					
					receive( http.getInputStream() );
					os.close();
					
					if (0 < wait_sec)
					{
						System.out.println( "Will request result in " + wait_sec + " seconds" );
						Thread.sleep( wait_sec * 1000 );
					}
					wait_sec   = 0;
					active_src = request;
				}
			
			
		} catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	static final void write_len( int len, OutputStream os ) throws IOException {
		os.write( len >> 16 );
		os.write( len >> 8 );
		os.write( len );
	}
	
	static int wait_sec = 0;
	
	static void receive( InputStream src ) throws Exception {
		switch (src.read())
		{
			case Protocol.Timeout:
				wait_sec = src.read();
				break;
			
			case Protocol.File:
				extract( src, dest_dir_path );
				
				String name = provided_file_path.getFileName().toString();
				if (name.endsWith( ".proto" ))
				{
					final Path path = dest_dir_path.resolve( name.substring( 0, name.length() - 5 ) + "java" );
					if (!Files.exists( path )) return;
					provided_file_path.toFile().setWritable( true );
					exit( "Please find converted file in " + path, 0 );
				}
				
				final Path path = dest_dir_path.resolve( name );
				if (!Files.exists( path )) exit( "Information received", 2 );
				
				String new_src = new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 );//from server, updated project source
				
				if (new_src.startsWith( "public" ))// file with imports
				{
					//getting header from current description file
					String cur_src = new String( Files.readAllBytes( provided_file_path ), StandardCharsets.UTF_8 );
					String header  = cur_src.substring( 0, get_class_declare( cur_src ) );//current descriptor header is - code, up from project class declaration position
					
					new_src = header + new_src;//extracted new source
				}
				
				//backup current version
				provided_file_path.getParent().resolve( provided_file_path.getFileName() + "_prev" ).toFile().delete();//otherwise java.nio.file.AccessDeniedException can arise
				Files.copy( provided_file_path, provided_file_path.getParent().resolve( provided_file_path.getFileName() + "_prev" ), StandardCopyOption.REPLACE_EXISTING );
				
				File curr_file = provided_file_path.toFile();
				
				curr_file.setWritable( true );
				final OutputStreamWriter os = new OutputStreamWriter( new FileOutputStream( curr_file, false ), StandardCharsets.UTF_8 );//replace content
				os.write( new_src );
				os.flush();
				os.close();
				curr_file.setLastModified( System.currentTimeMillis() + Integer.MAX_VALUE * 100L ); // successfully updated mark
				
				exit( "Please find generated files in " + dest_dir_path + " and previous version backup " + provided_file_path.getFileName() + "_prev", 0 );
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
	
	private static final Path dest_dir_path = FileSystems.getDefault().getPath( "" ).toAbsolutePath();//working/current directory
	
	private static final Logger LOG = Logger.getLogger( "ClientAgent" );
	
	private static Path    provided_file_path = null;
	static         boolean is_testing         = false;
	
	static final void set_provided_file_path( String path ) {
		provided_file_path = Paths.get( (is_testing = path.endsWith( "!" )) ? path.substring( 0, path.length() - 1 ) : path );
		is_testing         = !provided_file_path.endsWith( ".proto" );
	}
	
	private static String project = "";
	
	private static void extract( InputStream src, Path dst ) throws IOException {
		JarInputStream jar    = new JarInputStream( src );
		byte[]         buffer = new byte[1024];
		
		for (JarEntry je; (je = jar.getNextJarEntry()) != null; )//extracting everything from jar into destination_dir_path
		{
			final String name = je.getName();
			
			final File file = dst.resolve( name ).toFile();
			
			if (name.endsWith( "/" )) file.mkdirs();
			else
			{
				FileOutputStream out = new FileOutputStream( file );
				for (int len; -1 < (len = jar.read( buffer )); ) out.write( buffer, 0, len );
				
				if (file.getName().endsWith( "unirail.info" ))
				{
					System.out.println( "Information from " + file.toPath() );
					System.out.println( Files.lines( file.toPath(), StandardCharsets.UTF_8 ).collect( Collectors.joining( System.lineSeparator() ) ) );//print unirail.info message
				}
				jar.closeEntry();
				out.flush();
				out.close();
			}
		}
		jar.close();
	}
	
	static Path self_path() throws Exception {
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
	
	static final Properties props = new Properties();
	
	static final Pattern class_declaration_pattern = Pattern.compile( "\\s*(public|private)\\s+class\\s+(\\w+)\\s+((extends\\s+\\w+)|(implements\\s+\\w+( ,\\w+)*))?\\s*\\{" );
	
	static final int get_class_declare( String src ) {
		Matcher classes = class_declaration_pattern.matcher( src );
		return classes.find() ? classes.start( 1 ) : -1;
	}
	
	static final Pattern imports_pattern = Pattern.compile( "import\\p{javaIdentifierIgnorable}*\\p{javaWhitespace}+(?:static\\p{javaIdentifierIgnorable}*\\p{javaWhitespace}+)?(\\p{javaJavaIdentifierStart}[\\p{javaJavaIdentifierPart}\\p{javaIdentifierIgnorable}]*(?:\\p{javaWhitespace}*\\.\\p{javaWhitespace}*\\*|(?:\\p{javaWhitespace}*\\.\\p{javaWhitespace}*\\p{javaJavaIdentifierStart}[\\p{javaJavaIdentifierPart}\\p{javaIdentifierIgnorable}]*)+(?:\\p{javaWhitespace}*\\.\\p{javaWhitespace}*\\*)?))\\p{javaWhitespace}*;" );
	
	interface Protocol {
		int
				File    = 0,
				Request = 1,
				Timeout = 2;
	}
	
	static boolean is_prohibited( String name ) {
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




