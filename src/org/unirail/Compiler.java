// AdHoc protocol - data interchange format and source code generator
// Copyright 2019 Chikirev Sirguy, Unirail Group. All rights reserved.
// info@unirail.org
// https://github.com/cheblin/AdHoc-protocol
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
package org.unirail;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import java.net.URI;
import java.io.IOException;
import javax.tools.JavaFileObject;

public class Compiler extends ClassLoader {
	
	public Compiler() { super( ClassLoader.getSystemClassLoader() ); }
	
	public Map<String, BinaryCode> binaries = new HashMap<>();
	
	@Override
	protected Class<?> findClass( String name ) throws ClassNotFoundException {
		BinaryCode cc = binaries.get( name );
		return cc == null ? super.findClass( name ) : defineClass( name, cc.getByteCode(), 0, cc.getByteCode().length );
	}
	
	private final class FileManager extends ForwardingJavaFileManager<JavaFileManager> {
		
		FileManager() { super( javac.getStandardFileManager( null, null, null ) ); }
		
		
		@Override
		public JavaFileObject getJavaFileForOutput( JavaFileManager.Location location, String className, JavaFileObject.Kind kind, FileObject sibling ) throws IOException {
			try
			{
				final BinaryCode bin = new BinaryCode( className );
				binaries.put( className, bin );
				return bin;
			} catch (Exception e) { throw new RuntimeException( "Error while creating in-memory output file for " + className, e ); }
		}
		
		@Override
		public ClassLoader getClassLoader( JavaFileManager.Location location ) { return Compiler.this; }
	}
	
	
	private static final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
	
	private static SourceCode persistent_source = null;
	private        SourceCode case_source       = null;
	
	
	public static void addPersistentSource( Path src ) throws IOException {
		if (Files.isDirectory( src ))
			Files.walk( src ).forEach( path -> {
				if (!Files.isDirectory( path ))
				{
					String name = src.relativize( path ).toString().replace( File.separatorChar, '.' );
					if (name.endsWith( ".java" ))
						try
						{
							addPersistentSource( name.substring( 0, name.length() - 5 ), new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ) );
						} catch (Exception e) { e.printStackTrace(); }
				}
			} );
		else
		{
			String name = src.getFileName().toString();
			addPersistentSource( name.substring( 0, name.length() - 5 ), new String( Files.readAllBytes( src ), StandardCharsets.UTF_8 ) );
		}
	}
	
	public static void addPersistentSource( String className, String contents ) {
		persistent_source = new SourceCode( className, contents, persistent_source );
	}
	
	public void addSource( Path src ) throws IOException {
		if (Files.isDirectory( src ))
			Files.walk( src ).forEach( path -> {
				if (!Files.isDirectory( path ))
				{
					String name = src.relativize( path ).toString().replace( File.separatorChar, '.' );
					if (name.endsWith( ".java" ))
						try
						{
							addSource( name.substring( 0, name.length() - 5 ), new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 ) );
						} catch (Exception e) { e.printStackTrace(); }
				}
			} );
		else
		{
			String name = src.getFileName().toString();
			addSource( name.substring( 0, name.length() - 5 ), new String( Files.readAllBytes( src ), StandardCharsets.UTF_8 ) );
		}
	}
	
	public void addSource( String className, String contents ) { case_source = new SourceCode( className, contents, case_source == null ? persistent_source : case_source ); }
	
	
	public void compile() throws Exception                     {compile( true );}
	
	public void compile( boolean ignoreWarnings, String... options ) throws Exception {
		if (Compiler.javac == null) throw new NullPointerException( "Cannot find javac on the system. JRE is not enough, install JDK" );
		DiagnosticCollector<JavaFileObject> collector   = new DiagnosticCollector<>();
		FileManager                         fileManager = new FileManager();
		JavaCompiler.CompilationTask task = javac.getTask( null, fileManager, collector, Arrays.asList( options ), null, () -> new Iterator<JavaFileObject>() {
			SourceCode code = case_source;
			
			@Override public boolean hasNext() { return code != null; }
			
			@Override public JavaFileObject next() {
				SourceCode ret = code;
				code = code.next;
				return ret;
			}
		} );
		
		
		if (!task.call() || (0 < collector.getDiagnostics().size() && !ignoreWarnings))
		{
			final StringBuilder msg = new StringBuilder();
			for (Diagnostic d : collector.getDiagnostics())
			{
				msg.append( "\n" ).append( "King    =" ).append( d.getKind() );
				msg.append( "\n" ).append( "Line    =" ).append( d.getLineNumber() );
				msg.append( "\n" ).append( "Message =" ).append( d.getMessage( Locale.US ) ).append( "\n" );
			}
			throw new Exception( msg.toString() );
		}
	}
}


class BinaryCode extends SimpleJavaFileObject {
	private final ByteArrayOutputStream baos = new ByteArrayOutputStream() {
		@Override public synchronized byte[] toByteArray() { return buf; }
	};
	
	BinaryCode( String className ) throws Exception { super( new URI( className ), Kind.CLASS ); }
	
	final byte[] getByteCode()                      { return baos.toByteArray(); }
	
	@Override
	public OutputStream openOutputStream() throws IOException { return baos; }
}

class SourceCode extends SimpleJavaFileObject {
	private final String     contents;
	final         SourceCode next;
	
	SourceCode( String className, String contents, SourceCode next ) {
		super( URI.create( "string:///" + className.replace( '.', '/' ) + Kind.SOURCE.extension ), Kind.SOURCE );
		this.next     = next;
		this.contents = contents;
	}
	
	public CharSequence getCharContent( boolean ignoreEncodingErrors ) throws IOException { return contents; }
}


