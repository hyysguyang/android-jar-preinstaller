package org.lifecosys.androidjarpreinstaller

import java.io.File
import org.apache.commons.io.FileUtils
import sys.process._
import org.apache.commons.io.filefilter.{WildcardFileFilter, TrueFileFilter}
import scala.collection.JavaConverters._
import java.text.SimpleDateFormat
import java.util.Date


/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/24/12 12:44 AM
 */

object AndroidJarPreinstaller {
  val usage = "java -jar android-jar-preinstaller-1.0-SNAPSHOT-jar-with-dependencies.jar <jar-file-path> [android-avd-name]"


  def main(args: Array[String]) {

    if (args.length < 1) {
      println("Please provide the jar to be processed\n" + usage)
      return
    }

    require(new File(args(0)).exists(), "Jar not existed.....")
    val preinstaller = if (args.length > 1)
      new AndroidJarPreinstaller(avd = args(1))
    else new AndroidJarPreinstaller

    preinstaller.install(new File(args(0)))
  }
}

trait FileLike {
  def getAbsolutePath: String

  def /(child: String) = new File(getAbsolutePath + File.separator + child) with FileLike
}

class AndroidJarPreinstaller(avd: String = "default", baseDir: File with FileLike = new File("temp") with FileLike) {

  val dummyLogger = ProcessLogger({
    s =>
  })

  val androidHome = new File(System.getenv("ANDROID_HOME"))
  require(androidHome.exists())

  def install(jar: File) = {
    val dexJarList = createDexJar(jar)

    createRamdisk(dexJarList)

    Process("emulator @" + avd).run()
    "adb wait-for-device" !

    "adb shell mkdir -p /data/framework/" !

    dexJarList.foreach {
      file => ("adb push " + file.getAbsolutePath + " /data/framework/") !
    }

    println("Push all jar to /data/framework success.....")
    println("Please close emulator and restart emulator, then all you jar will be available for other application")
    println("\t\temulator @" + avd)

    System.exit(0)
  }


  def createDexJar(jar: File) = {
    baseDir.mkdir()
    FileUtils.forceDelete(baseDir)
    baseDir.mkdir()
    val classes = baseDir / "classes"
    classes mkdir()

    val splitJar = baseDir / "split-jar"
    splitJar mkdir()

    val dexJar = baseDir / "dex-jar"
    dexJar mkdir()

    Process("jar -xf " + jar.getAbsolutePath, classes) !

    implicit def stringToProcess(command: String) = Process(command, baseDir)

    val components = listFiles(classes).map {
      file => file.getAbsolutePath.stripPrefix(classes.getAbsolutePath + "/").replace("/", "-") -> file
    }
    components.foreach(println(_))

    components.foreach {
      component => {
        val classDir: String = component._2.getAbsolutePath.stripPrefix(classes.getAbsolutePath + "/")
        "jar cvfm split-jar/%s.jar %s/META-INF/MANIFEST.MF -C %s %s".format(component._1, classes.getAbsolutePath, classes.getAbsolutePath, classDir) ! (dummyLogger)

        FileUtils.forceDelete(component._2)
      }
    }

    val dx = FileUtils.listFiles(androidHome, new WildcardFileFilter("dx"), TrueFileFilter.INSTANCE).asScala.filter(_.canExecute)

    require(!dx.isEmpty, "Can't locate dx within android home....")

    splitJar.listFiles().foreach {
      file =>
        "%s --dex --output=dex-jar/%s split-jar/%s".format(dx.head.getAbsolutePath, file.getName, file.getName) ! (dummyLogger)
    }

    dexJar.listFiles()
  }

  def createRamdisk(dexJarList: Array[File]) {
    val ramdiskDir = baseDir / "ramdisk-dir"
    def updateRamdisk(ramdisk: File) {
      ramdiskDir mkdir()
      FileUtils.forceDelete(ramdiskDir)
      ramdiskDir mkdir()

      val ramdiskTemp: File with FileLike = ramdiskDir / "temp"
      ramdiskTemp mkdir()

      val ramdiskBackup = new File(ramdisk.getParentFile.getAbsolutePath + ("/ramdisk.img.original-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date)))
      FileUtils.copyFile(ramdisk, ramdiskBackup)
      println("Backup " + ramdisk.getAbsolutePath + " to " + ramdiskBackup.getAbsolutePath)
      FileUtils.copyFile(ramdisk, ramdiskDir / "ramdisk.cpio.gz")

      Process("gzip -d ramdisk.cpio.gz", ramdiskDir) !

      FileUtils.copyFile(ramdiskDir / "ramdisk.cpio", ramdiskTemp / "ramdisk.cpio")

      Process("cpio -i -F ramdisk.cpio", ramdiskTemp) !

      val initRCFile: File with FileLike = ramdiskTemp / "init.rc"
      FileUtils.readFileToString(initRCFile)

      val extraClasspath = ":/data/framework/" + dexJarList.map(_.getName).mkString(":/data/framework/")

      val finalInitRC = FileUtils.lineIterator(initRCFile).asScala.map {
        case line if line.trim.startsWith("export BOOTCLASSPATH") && !line.contains(extraClasspath) => line + extraClasspath

        case line => line
      }

      FileUtils.writeLines(initRCFile, finalInitRC.toList.asJavaCollection)


      Process("cpio -i -t -F " + ramdiskDir.getAbsolutePath + "/ramdisk.cpio") #|
        Process("cpio -o -H newc -O " + ramdiskDir.getAbsolutePath + "/ramdisk_new.img", ramdiskTemp) !

      "gzip " + ramdiskDir.getAbsolutePath + "/ramdisk_new.img" !

      FileUtils.copyFile(new File(ramdiskDir.getAbsolutePath + "/ramdisk_new.img.gz"), ramdisk)

      println("Add jar to BOOTCLASSPATH success for ramdisk " + ramdisk.getAbsolutePath)
    }

    val ramdisks = FileUtils.listFiles(androidHome, new WildcardFileFilter("ramdisk.img"), TrueFileFilter.INSTANCE).asScala

    ramdisks foreach updateRamdisk _
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
