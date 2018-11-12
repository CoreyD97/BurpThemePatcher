##Burp Theme Patcher
As of v2.0.10beta, Burp Suite contains an interface used as a form of "color palette".

This tool aims to simplify the identification of this interface and the associated manager within the jars.

The tool will also extract the jars' built in palettes and generate a wrapper class to override the used palette.
 
Usage: `java -jar BurpThemePatcher.jar <YOUR BURP JAR HERE>`

Two built-in palettes are currently available, the default palette and the dark palette.

**Note: I do not know which methods are for which elements. You'll have to use trial and error to find the one you require.**


###Usage 
1) `java -jar BurpThemePatcher.jar <YOUR BURP JAR HERE>` to extract the palettes.

2) Edit the palettes as you wish and edit `BurpThemer.java` to enable your palette.

3) (Optional) Set UIManager entries as described in `BurpThemer.java`. Where the Darcula theme is in use, see [here](https://github.com/bulenkov/Darcula/blob/master/src/com/bulenkov/darcula/darcula.properties) for the properties overriden by the LaF.

4) Make the patching script executable `chmod +x ./patch.sh`

5) Execute the patch script `./patch.sh`

6) Run your themed jar!