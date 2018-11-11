import jd.common.loader.JarLoader;
import jd.common.preferences.CommonPreferences;
import jd.common.printer.text.PlainTextPrinter;
import jd.core.Decompiler;
import jd.core.loader.LoaderException;
import jd.core.printer.Printer;
import jd.core.process.DecompilerImpl;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;

public class ClassCompiler {

    final ClassLoader classLoader;
    final Decompiler decompiler;
    final CommonPreferences prefs;
    final File burpJar;
    final JarLoader jarLoader;
    final JavaCompiler javaCompiler;

    public ClassCompiler(File burpJar, ClassLoader classLoader) throws LoaderException {
        this.burpJar = burpJar;
        this.classLoader = classLoader;
        this.jarLoader = new JarLoader(burpJar);
        this.decompiler = new DecompilerImpl();
        this.prefs = new CommonPreferences(true, false, true, true, true, false);
        this.javaCompiler = ToolProvider.getSystemJavaCompiler();
        if(this.javaCompiler == null){
            System.out.println("Could not load the system Java compiler. Ensure the JDK is installed and configured.");
            System.exit(1);
        }
    }


    public String decompile(String classPath){
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(bos, true);
        Printer stringPrinter = new PlainTextPrinter(prefs, printStream);

        try {
            decompiler.decompile(prefs, jarLoader, stringPrinter, classPath);
        } catch (LoaderException e) {
            e.printStackTrace();
            return null;
        }
        return bos.toString();
    }

    public boolean recompile(ArrayList<File> files){
        DiagnosticCollector diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(diagnostics, null, null);
        try {
            fileManager.setLocation(StandardLocation.CLASS_PATH, new ArrayList<>(Arrays.asList(burpJar, new File("BurpDark.class"))));
        } catch (IOException e) {
            e.printStackTrace();
        }
        ArrayList<String> options = new ArrayList<>();


        Iterable<? extends JavaFileObject> compilationUnit = fileManager.getJavaFileObjectsFromFiles(files);
        JavaCompiler.CompilationTask task = javaCompiler.getTask(null, fileManager, diagnostics, options, null, compilationUnit);

        if(!task.call()){
            for (Object diagnostic : diagnostics.getDiagnostics()) {
                System.out.println(diagnostic.toString());
            }
            System.err.println("Could not patch the jar. The patched classes could not be recompiled.");
            return false;
        }
        return true;
    }
}
