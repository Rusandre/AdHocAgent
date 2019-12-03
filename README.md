# AdHocAgent
This is source code of the program which checks **AdHoc** protocol description file, pack and upload it. 
Then waiting for server reply with generated result, download and unpack it.

As transport, **AdHocAgent** can use raw **TCP** / **HTTP** or **IMAP**.  
At the beginning stage of your protocol project, when it changes frequently and getting the generated code faster is important, using raw 
**TCP** (or **HTTP** if you have to use a proxy) is the most effectively.  
Later, when your project becomes mature, stabilizes and the confidence in the generated code becomes more important. You can switch to **IMAP** transport where, 
in addition to code generation, possible to order testing of the generated code, and yes, it takes a longer time.

To use **IMAP**, please activate, in your mailbox settings, this transport access option. Recently e-mail providers highly restrict access to their system, so for your mailbox, 
you have to relax this rule. In gmail, for example, please enter to your account security settings and let access for less secure apps.

![Capture](https://user-images.githubusercontent.com/29354319/69202440-a0cf1e80-0b7c-11ea-9e52-6c81655a38b4.PNG)

In **AdHocAgent** source directory rename one of the best fit for your e-mail provider `.properties` files in to `client.properties`.

Update it content: 

`server.tcp` option let you point server generator host, its port and protocol ( HTTP, if it starts with `http://`)   
Set `mail.box` property to your mailbox. **Also it used as user name in **TCP/HTTP** as a transport case.**   
Some e-mail providers require additional login to connect to their service, provide it with `mail.login` option.  

Option `description.file.path` should contains path to your project description file, otherwise provide it as the first argument of the command line.   
**If this path ends with !(exclamation) mark generated code will be tested. Ths option is available with **IMAP** transport only.**   

The `annotations.directory` point to the directory with 'org', top **AdHoc** [annotations directory](https://github.com/cheblin/AdHoc) inside, 
same annotations you used to compose the protocol description.

Your mailbox password, if you use **IMAP** transport, you can 
- put in the `client.properties` file as `mail.password` option
- provide as the second argument of the **AdHocAgent** command line 
- enter to the popup textbox dialog, if program run, but cannot find any. 

Ensure [**JDK 8**](https://www.oracle.com/technetwork/java/javase/downloads/index.html) is installed, `javac` is available in console and in the path.   
This program has one external dependency: [javamail](https://javaee.github.io/javamail/) and need a reference to `javax.mail.jar`.  
When compiling **AdHocAgent** you can 
 - embed `client.properties` file inside **JAR** binary or
 - put it next to jar/class or 
 - copy to the current/working directory
 
If you get **IMAP** connection problem, uncommented `mail.debug = true` option lets you get a full `javamail` connection log. Find details about `mail.debug` in [JavaMail documentation](https://javaee.github.io/javamail/FAQ).  
 
After complete your protocol description file.
Compile **AdHocAgent** by yourself or take ready one jar file in the `bin` directory.
Run **AdHocAgent**, it will try to find the description file, recognize the current workflow stage and:
- compile, parse and check all used in the description names. **Programming languages used a set of reserved words. Using them and underscore, as first / last char of the name, are prohibited.**
- if the check names phase is passed, the program composes message with the protocol description file inside. If this file version was never sent, upload it to the server.
- or/and try to receive server reply.
- if getting the reply, **AdHocAgent** expand generated code in the [current/working directory](https://en.wikipedia.org/wiki/Working_directory) of the **AdHocAgent** process

On windows OS, if you create shortcut to run **AdHocAgent**, provide working directory, the place where generated code will be extracted, as shown on th picture.
 
![image](https://user-images.githubusercontent.com/29354319/69940309-eb597f00-151c-11ea-922f-1795eccfa796.png)

On our side, when the server receives your specification it will checks their correctness, generates requested source code in specified languages, generate several tests and run them. If all tests passed, compose reply and put inside
- generated protocol handler code
- last generated test
- example of using generated API 

If any error occurred, you will be notified of a possible delay and the AdHoc team will deal with the problems.
