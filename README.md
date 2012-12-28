android-jar-preinstaller
========================

Preinstall large jar to android emulator for other android application, such as preinstall scala-library to emulator to
avoid deploy and proguard scala library when develop android application with scala.


Why we need it?

Proguard and Dex large jar (than 5M) is very slow when develop android application, so preinstall large jar to android
emulator will make development more productive. Besides, dx will throw expception for large jar, so you have to split
large jar to deploy it even you don't want preinstall it. One typical scenario is develop android application with scala,
it's very slowly to proguard scala-library.jar.


How build it?
git clone https://github.com/hyysguyang/android-jar-preinstaller.git
cd android-jar-preinstaller && mvn install

How to use it?
TODO



