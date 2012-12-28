package org.lifecosys.androidjarpreinstaller

import java.io.File
import sys.process.{ProcessLogger, Process}
import java.util.Date
import org.apache.commons.io.FileUtils


/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/24/12 12:44 AM
 */

object AndroidJarPreinstaller {
  def main(args: Array[String]) {
    //"/Develop/DevelopTools/scala-2.9.1.final/lib/scala-library.jar"
    val jar: File = new File(args(0))
    require(jar.exists())
    new AndroidJarPreinstaller().install(jar)
  }
}

class AndroidJarPreinstaller {

  trait FileLike {
    def getAbsolutePath: String

    def /(child: String) = {
      new File(getAbsolutePath + File.separator + child) with FileLike
    }
  }

  def install(jar: File) = {
    val dexJarList = createDexJar(temp,jar)

    createRamdisk(temp, dexJarList)

    Process("emulator @17").run()
    Process("adb wait-for-device") !

    Process("adb shell mkdir -p /data/framework/") !

    dexJarList.foreach {
      file => Process("adb push " + file.getAbsolutePath + " /data/framework/") !
    }

    println("Push all jar to /data/framework success.....")
    println("Please close emulator and use below command to start emulator, then all you jar will be available for other application")
    println("\t\temulator @17 -ramdisk /tmp/scala-dex/ramdisk-dir/ramdisk_new.img")
  }


  def createDexJar(jar:File) = {
    val temp = new File("temp") with FileLike
    temp.mkdir()
    FileUtils.forceDelete(temp)
    temp.mkdir()
    val base = temp / "base"
    base mkdir()

    val splitJar = temp / "scala-split-jar"
    splitJar mkdir()

    val dexJar = temp / "scala-dex-jar"
    dexJar mkdir()

    Process("jar -xf "+ jar.getAbsolutePath, base) !
    //    Process("jar -xf "+System.getenv("SCALA_HOME")+"/lib/scala-library.jar", base) !
    //    Process("jar -xf "+System.getenv("SCALA_HOME")+"/lib/scala-library.jar", base) !
    //
    //


    implicit def stringToProcess(command: String) = Process(command, temp)

    //    val components = Array("collection-immutable", "collection-mutable", "collection-parallel", "collection", "util", "reflect")
    val components = listFiles(base).map {
      file => file.getAbsolutePath.stripPrefix(base.getAbsolutePath + "/").replace("/", "-") -> file
    }
    components.foreach(println(_))

    components.foreach {
      component => {
        val classDir: String = component._2.getAbsolutePath.stripPrefix(base.getAbsolutePath + "/")
        "jar cvfm scala-split-jar/%s.jar base/META-INF/MANIFEST.MF -C %s %s".format(component._1, base.getAbsolutePath, classDir) !

        FileUtils.forceDelete(component._2)
      }
    }

    //    "jar cvfm scala-split-jar/" -base.jar base/META-INF/MANIFEST.MF -C base ." !

    splitJar.listFiles().foreach {
      file =>
        "dx --dex --output=scala-dex-jar/%s scala-split-jar/%s".format(file.getName, file.getName) !
    }


    dexJar.listFiles()
  }

  def createRamdisk(temp: File with FileLike, dexJarList: Array[File]) {
    val ramdiskDir = temp / "ramdisk-dir"
    ramdiskDir mkdir()

    val ramdiskTemp: File with FileLike = ramdiskDir / "temp"
    ramdiskTemp mkdir()

    val file: File = new File("/Develop/DevelopTools/android-sdk-linux/system-images/android-17/armeabi-v7a/ramdisk.img") with FileLike

    FileUtils.copyFile(file, new File(file.getParentFile.getAbsolutePath + ("/ramdisk.img.bak" + System.currentTimeMillis())))
    FileUtils.copyFile(file, ramdiskDir / "ramdisk.cpio.gz")

    Process("gzip -d ramdisk.cpio.gz", ramdiskDir) !

    FileUtils.copyFile(ramdiskDir / "ramdisk.cpio", ramdiskTemp / "ramdisk.cpio")

    Process("cpio -i -F ramdisk.cpio", ramdiskTemp) !

    val initRCFile: File with FileLike = ramdiskTemp / "init.rc"
    FileUtils.readFileToString(initRCFile)

    val lines = FileUtils.lineIterator(initRCFile)

    import scala.collection.JavaConverters._

    val finalInitRC = lines.asScala.map {
      case line if line.trim.startsWith("export BOOTCLASSPATH") => line + ":/data/framework/" + dexJarList.map(_.getName).mkString(":/data/framework/")
      case line => line
    }

    FileUtils.writeLines(initRCFile, finalInitRC.toList.asJavaCollection)


    Process("cpio -i -t -F " + ramdiskDir.getAbsolutePath + "/ramdisk.cpio") #|
      Process("cpio -o -H newc -O " + ramdiskDir.getAbsolutePath + "/ramdisk_new.img", ramdiskTemp) !


    println("Add jar to BOOTCLASSPATH success, the new ramdisk is:\n"+ramdiskDir.getAbsolutePath + "/ramdisk_new.img")
    //    FileUtils.copyFile(new File(ramdiskDir.getAbsolutePath + "/ramdisk_new.img"),file)
  }

  def deleteDirectory(dir: File, postfixOfFilePath: String): Unit = dir.listFiles().foreach {
    case file if file.getAbsolutePath.endsWith(postfixOfFilePath) => FileUtils.forceDelete(file)
    case file if file.isDirectory => deleteDirectory(file, postfixOfFilePath)
    case _ =>
  }

  def fileSize(file: File): Long = file match {
    case f if f.isFile => f.length()
    case f => f.listFiles().foldLeft(0L)(_ + fileSize(_))
  }

  def listFiles(base: File, minLength: Long = 1024 * 1024 * 3, maxLength: Long = 1024 * 1024 * 6): List[File] = base.listFiles().foldLeft(List[File]()) {
    (list, file) => file match {
      case file if file.isDirectory && fileSize(file) > maxLength => listFiles(file, minLength, maxLength) ::: file :: list
      case file if file.isDirectory && fileSize(file) > minLength => file :: list
      case _ => list
    }
  }


}
