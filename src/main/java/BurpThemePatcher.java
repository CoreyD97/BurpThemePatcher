import com.bulenkov.darcula.DarculaLaf;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import jd.core.loader.LoaderException;
import org.apache.commons.io.FileUtils;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.reflections.scanners.MethodParameterScanner;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.List;

public class BurpThemePatcher {

    File jar;
    URLClassLoader classLoader;
//    ClassCompiler classCompiler;
    ClassPool classPool;

    Class colorManager;
    Class colorPaletteInterface;
    HashMap<Class, Object> colorPalettes;
    ArrayList<String> classFileNames;

    public static void main(String[] args) throws IOException, LoaderException, NotFoundException, CannotCompileException {
//        File jar = new File("/home/corey/IdeaProjects/BurpThemePatcher/burpcommunity_1.7.36.jar");
        if(args.length != 1){
            System.out.println("Usage: java -jar BurpThemePatcher.jar");
            System.exit(0);
        }
        File jar = new File(args[0]);
        if(!jar.exists()){
            System.err.println("Could not find specified jar. Exiting...");
        }
        BurpThemePatcher patcher = new BurpThemePatcher(jar);
        patcher.patch();
    }

    public BurpThemePatcher(File jar) throws MalformedURLException {
        this.jar = jar;
        this.classLoader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, BurpThemePatcher.class.getClassLoader());
        this.classPool = ClassPool.getDefault();
        this.classPool.insertClassPath(new LoaderClassPath(this.classLoader));
        this.classFileNames = new ArrayList<>();
//        try {
//            this.classCompiler = new ClassCompiler(this.jar, classLoader);
//        } catch (LoaderException e) {
//            System.err.println("Could not make JarLoader.");
//            System.exit(1);
//        }
    }

    private void patch(){
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame();
            try {
                UIManager.setLookAndFeel(new DarculaLaf());
            } catch (UnsupportedLookAndFeelException e) {
                e.printStackTrace();
                System.exit(0);
            }
            try {
                generateTemplates();
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                generateScripts();
            } catch (IOException e) {
                System.err.println("Could not generate the patching scripts. Please compile and update the jar manually.");
            }
        });
    }

    private void generateScripts() throws IOException {
        String linux = "#!/bin/bash\n" +
                "jar=" + jar.getAbsolutePath() + "\n" +
                "javac -cp $jar *.java\n" +
                "cp $jar BurpSuiteThemed.jar\n" +
                "unzip $jar META-INF/MANIFEST.MF  \n" +
                "sed -i 's/Main-Class: burp.StartBurp/Main-Class: BurpThemer/' META-INF/MANIFEST.MF\n" +
                "zip -ur BurpSuiteThemed.jar META-INF *.class\n" +
                "echo \"Patch complete! Execute the jar to see your changes.\"";
        FileUtils.writeStringToFile(new File("patch.sh"), linux);
    }

    private void generateTemplates() throws IOException {
        Reflections reflections = new Reflections("burp", classLoader);
        Reflections methodReflections = new Reflections("burp", classLoader, new MethodParameterScanner());

        Set<Method> returnColorMethods = methodReflections.getMethodsReturn(Color.class);
        Map<Class, Integer> colorClassMethodMap = new HashMap<>();
        for (Method returnColorMethod : returnColorMethods) {
            Class c = returnColorMethod.getDeclaringClass();
            if(colorClassMethodMap.containsKey(c)){
                colorClassMethodMap.put(c, colorClassMethodMap.get(c)+1);
            }else{
                colorClassMethodMap.put(c, 1);
            }
        }

        System.out.println("Found " + colorClassMethodMap.size() + " classes with methods returning Color types.");
        System.out.println("Identifying color palette interface.");
        for (Class clazz : colorClassMethodMap.keySet()) {
            if(clazz.isInterface()){
                colorPaletteInterface = clazz;
                System.out.println("Found color palette interface " + colorPaletteInterface.getName());
                break;
            }
        }

        System.out.println("Finding color manager class.");

        List<Class> potentialColorManagers = new ArrayList<>();
        for (Class classWithReturnColorMethods : colorClassMethodMap.keySet()) {
            Set<Field> paletteFields = ReflectionUtils.getAllFields(classWithReturnColorMethods, ReflectionUtils.withType(colorPaletteInterface));
            if(paletteFields.size() != 0) potentialColorManagers.add(classWithReturnColorMethods);
        }

        if(potentialColorManagers.size() > 1) {
            System.out.println("Found multiple potential color managers. TODO: find the correct one.");
            System.out.println("This wasn't an issue when I wrote the patcher. Tweet me @CoreyD97 with the version you're using.");
            System.exit(0);
        }
        if(potentialColorManagers.size() == 0){
            System.out.println("Found no potential color managers... TODO: Update.");
            System.out.println("This wasn't an issue when I wrote the patcher. Tweet me @CoreyD97 with the version you're using.");
            System.exit(0);
        }
        colorManager = potentialColorManagers.get(0);

        Set<Class> colorPalettes = getClassImplementationsOf(reflections, colorPaletteInterface);
        Method[] colorPaletteInterfaceMethods = colorPaletteInterface.getDeclaredMethods();
        this.colorPalettes = new HashMap<>();
        int i=1;
        for (Class colorPalette : colorPalettes) {
            Object colorPaletteInstance = null;
            try {
                colorPaletteInstance = colorPalette.newInstance();
                this.colorPalettes.put(colorPalette, colorPaletteInstance);
            } catch (InstantiationException | IllegalAccessException  e) {
                System.err.println("Skipping color palette: " + colorPalette.getName() + ". Could not instantiate.");
                System.err.println("Tweet me @CoreyD97 with your version number and I'll try to fix this.");
                continue;
            }

            //Turn color palette into editable classes.
            StringBuilder sb = new StringBuilder();
            String className = "CustomColorPalette_" + i;
            classFileNames.add(className);
            sb.append("import java.awt.Color;\n" +
                    "public class " + className + " implements " + colorPaletteInterface.getName() + "{\n");

            for (Method colorPaletteInterfaceMethod : colorPaletteInterfaceMethods) {
                sb.append("     public Color " + colorPaletteInterfaceMethod.getName() + "(){\n");
                try {
                    Color returnColor = (Color) colorPaletteInterfaceMethod.invoke(colorPaletteInstance);
                    sb.append(String.format("           return new Color(%d, %d, %d);\n", returnColor.getRed(), returnColor.getGreen(), returnColor.getBlue()));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    sb.append("//Could not determine actual return color. Sorry!\n");
                    sb.append("return new Color(0,0,0);\n");
                }
                sb.append("     }\n");
            }

            sb.append("}\n");

            System.out.println("Saving color palette template to file.");
            File outputFile = new File(className + ".java");
            try {
                FileUtils.writeStringToFile(outputFile, sb.toString());
            } catch (IOException e) {
                System.err.println("Could not save to file: " + outputFile.toPath());
            }
            i++;
        }

        buildThemeWrapper(this.classFileNames);
    }

    static <T> Object getClassValue(Class clazz, T instance, String fieldname) {
        try {
            Field field = clazz.getDeclaredField(fieldname);
            unfinal(field);
            boolean accessible = field.isAccessible();
            field.setAccessible(true);
            Object o = field.get(instance);
            field.setAccessible(accessible);
            return o;
        } catch (IllegalAccessException | NoSuchFieldException e) {
            return null;
        }
    }

    static void setClassValue(Class clazz, Object instance, String fieldname, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(fieldname);
        unfinal(field);
        boolean accessible = field.isAccessible();
        field.setAccessible(true);
        field.set(instance, value);
        field.setAccessible(accessible);
    }

    static void unfinal(Field field) throws NoSuchFieldException, IllegalAccessException {
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }

    public String colorToHex(Color c){
        return String.format("new Color(%d,%d,%d)", c.getRed(), c.getGreen(), c.getBlue());
    }

    public void buildThemeWrapper(ArrayList<String> classFileNames) throws IOException {
        Field managerField = (Field) ReflectionUtils.getAllFields(colorManager, ReflectionUtils.withType(colorPaletteInterface)).toArray()[0];

        StringBuilder sb = new StringBuilder();
        for (String classFileName : classFileNames) {
            sb.append("         " + colorPaletteInterface.getName() + " palette" + classFileName + " = new " + classFileName + "();\n");
        }
        String paletteInitialization = sb.toString();
        sb = new StringBuilder();
        for (String classFileName : classFileNames) {
            sb.append("        //setClassValue(" + colorManager.getSimpleName() + ".class, null, \""
                    + managerField.getName() + "\", palette" + classFileName + ");\n");
        }
        String paletteOptions = sb.toString();

        String burpDark = "import burp.StartBurp;\n" +
                "import " + colorManager.getName() + ";\n" +
                "import " + colorPaletteInterface.getName() + ";\n" +
                "import java.lang.reflect.Field;\n" +
                "import java.lang.reflect.Modifier;\n" +
                "\n" +
                "public class BurpThemer {\n" +
                "\n" +
                "    public static void main(String[] args) throws NoSuchFieldException, IllegalAccessException {\n" +
                "        burp.StartBurp.main(args);\n" +
                "        //Must also set UIManager values to override Look and Feel. e.g.\n" +
                "        //UIManager.put(KEY,VALUE);\n" +
                         paletteInitialization + //Palette initializations
                "        //Uncomment one of the options below to set the palette.\n" +
                         paletteOptions +
                "    }\n" +
                "\n" +
                "    static <T> Object getClassValue(Class clazz, T instance, String fieldname) {\n" +
                "        try {\n" +
                "            Field field = clazz.getDeclaredField(fieldname);\n" +
                "            unfinal(field);\n" +
                "            boolean accessible = field.isAccessible();\n" +
                "            field.setAccessible(true);\n" +
                "            Object o = field.get(instance);\n" +
                "            field.setAccessible(accessible);\n" +
                "            return o;\n" +
                "        } catch (IllegalAccessException | NoSuchFieldException e) {\n" +
                "            return null;\n" +
                "        }\n" +
                "    }\n" +
                "    \n" +
                "    static void setClassValue(Class clazz, Object instance, String fieldname, Object value) throws NoSuchFieldException, IllegalAccessException {\n" +
                "        Field field = clazz.getDeclaredField(fieldname);\n" +
                "        unfinal(field);\n" +
                "        boolean accessible = field.isAccessible();\n" +
                "        field.setAccessible(true);\n" +
                "        field.set(instance, value);\n" +
                "        field.setAccessible(accessible);\n" +
                "    }\n" +
                "\n" +
                "    static void unfinal(Field field) throws NoSuchFieldException, IllegalAccessException {\n" +
                "        Field modifiersField = Field.class.getDeclaredField(\"modifiers\");\n" +
                "        modifiersField.setAccessible(true);\n" +
                "        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);\n" +
                "    }\n" +
                "}\n";
        FileUtils.writeStringToFile(new File("BurpThemer.java"), burpDark);

        System.out.println();
        System.out.println("All templates built! Now edit the template files to the chosen colors and edit BurpThemer.java to select your palette.");

        sb = new StringBuilder();
        sb.append("javac -cp " + jar.getAbsolutePath() + ":.");
        for (String classFileName : classFileNames) {
            sb.append(" " + classFileName + ".java");
        }
        sb.append(" BurpThemer.java");

        System.out.println();
        System.out.println("Run the patch script...");
        System.out.println("Or compile using `" + sb.toString() + "` and drop the compiled classes into your jar then replace \"Main-Class: burp.StartBurp\" with \"Main-Class: BurpThemer\" in META-INF/MANIFEST.MF");
    }

    public static <T> Set<Class<? extends T>> getDirectSubClassesOf(Reflections reflections, Class<T> superClass){
        Set<Class<? extends T>> subClasses = reflections.getSubTypesOf(superClass);
        subClasses.removeIf(subClass -> !(subClass.getSuperclass() != null && subClass.getSuperclass().equals(superClass)));
        subClasses.removeIf(subClass -> !subClass.getPackage().getName().equals("burp"));
        return subClasses;
    }

    public static <T> Set<Class<? extends T>> getClassImplementationsOf(Reflections reflections, Class<T> superClass){
        Set<Class<? extends T>> subClasses = reflections.getSubTypesOf(superClass);
        subClasses.removeIf(subClass -> !Arrays.asList(subClass.getInterfaces()).contains(superClass));
        subClasses.removeIf(subClass -> !subClass.getPackage().getName().equals("burp"));
        return subClasses;
    }
}