package org.squbs.unicomplex

import java.io.{File, FileInputStream}
import java.util.jar.{JarFile, Manifest}

import concurrent.Await
import concurrent.duration._
import akka.actor.{Actor, Props}
import akka.pattern.ask
import akka.util.Timeout
import scala.collection.mutable.ArrayBuffer

object Bootstrap extends App {
    
  println("Booting unicomplex")
  
  val startTime = System.nanoTime

  val cubes = System.getProperty("java.class.path")
		  		.split(File.pathSeparator)
		  		.map(getManifestInfo).filter(_ != null)
          .map(getInitInfo).flatten.sortWith(_.startupType.id < _.startupType.id)
		  		.map(startComponents).flatten.filter(_ != null)
		  		
  val elapsed = (System.nanoTime - startTime) / 1000000
  println(s"squbs started in $elapsed milliseconds")

  object StartupType extends Enumeration {
    type StartupType = Value
    val
      // Identifies extensions
      EXTENSIONS,

      // Identifies actors as startup type
      ACTORS,

      // Identifies service as startup type
      SERVICES = Value


  }

  case class InitInfo(jarPath: String, symName: String, version: String,
                      entries: String, startupType: StartupType.Value)


  private[this] def getManifestInfo(jarName: String): (String, Manifest) = {
    val jarFile = new File(jarName)
    var stream: FileInputStream = null
    try {
      if (jarFile.isDirectory) {
        val manifestFile = new File(jarName, "META-INF/MANIFEST.MF")
        if (manifestFile.isFile) {
          stream = new FileInputStream(manifestFile)
          (jarName, new Manifest(stream))
        } else null
      } else if (jarFile.isFile) {
        val manifest = new JarFile(jarFile).getManifest
        if (manifest == null) null else (jarName, manifest)
      } else null
    } catch {
      case e: Exception =>
        println(s"${e.getClass.getName} reading manifest from $jarName.\n ${e.getMessage}")
        null
    } finally if (stream != null) stream.close()
  }

  private[this] def getInitInfo(manifestInfo: (String, Manifest)) = {
    val jarPath = manifestInfo._1
    val attrs = manifestInfo._2.getMainAttributes
    val symName = attrs.getValue("Bundle-SymbolicName")
    val version = attrs.getValue("Bundle-Version")
    val initList = ArrayBuffer.empty[InitInfo]
    if (symName != null) {
      val actors = attrs.getValue("X-squbs-actors")
      if (actors != null) initList += InitInfo(jarPath, symName, version, actors, StartupType.ACTORS)
      val routeDefs = attrs.getValue("X-squbs-services")
      if (routeDefs != null) initList += InitInfo(jarPath, symName, version, routeDefs, StartupType.SERVICES)
      val extensions = attrs.getValue("X-squbs-extensions")
      if (extensions != null) initList += InitInfo(jarPath, symName, version, extensions, StartupType.EXTENSIONS)
    }
    initList.toList
  }


  private[this] def startComponents(initInfo : InitInfo) = {
    initInfo.startupType match {
      case StartupType.ACTORS =>
        startActors(initInfo)
      case StartupType.SERVICES =>
        startRoutes(initInfo)
      case StartupType.EXTENSIONS =>
        startExtensions(initInfo)
    }
  }

  def startActors(initInfo: InitInfo) = {
    import initInfo.{jarPath, symName, version, entries}
    import Unicomplex.actorSystem
    val cubeActor = actorSystem.actorOf(Props[CubeSupervisor],
      symName.substring(symName.lastIndexOf('.') + 1))

    def startActor(actor: String): (String, String, Class[_]) = {
      val nameEnd = actor.indexOf(';')
      val className = if (nameEnd == -1) actor else actor.substring(0, nameEnd)
      val props =
        if (nameEnd < 0) Map.empty[String, String]
        else parseOptions(actor.substring(nameEnd + 1)).toMap

      try {
        val clazz = Class.forName(className, true, getClass.getClassLoader)
        val actorClass = clazz.asSubclass(classOf[Actor])
        cubeActor ! StartCubeActor(Props(actorClass),
          props.getOrElse("name", className.substring(className.lastIndexOf('.') + 1)))
        (symName, version, clazz)
      } catch {
        case e: Exception =>
          val t = getRootCause(e)
          println(s"Can't load actor: $className.\n" +
            s"Cube: $symName $version\n" +
            s"Path: $jarPath\n" +
            s"${t.getClass.getName}: ${t.getMessage}")
          null
      }
    }

    val actorInfo = entries.split(',').map(startActor)
    println(s"Started cube $symName $version")
    actorInfo
  }

  def startRoutes(initInfo: InitInfo) = {
    import initInfo.{jarPath, symName, version, entries}
    def startRoute(routeDef: String): (String, String, Class[_]) =
      try {
        import ServiceRegistry.registrar
        val clazz = Class.forName(routeDef, true, getClass.getClassLoader)
        val routeClass = clazz.asSubclass(classOf[RouteDefinition])
        if (registrar() == null) {
          val startTime = System.nanoTime
          implicit val timeout = Timeout(1000 milliseconds)
          val ackFuture = Unicomplex.uniActor ? StartWebService
          // Block for the web service to be started.
          Await.ready(ackFuture, timeout.duration)
          // Tight loop making sure the registrar is in place
          while (registrar() == null) {
            registrar.await
          }
          val elapsed = (System.nanoTime - startTime) / 1000000
          println(s"Web Service started in $elapsed milliseconds")
        }
        registrar() ! Register(routeClass.newInstance)
        (symName, version, clazz)
      } catch {
        case e: Exception =>
          val t = getRootCause(e)
          println(s"Can't load route definition $routeDef.\n" +
            s"Cube: $symName $version\n" +
            s"Path: $jarPath\n" +
            s"${t.getClass.getName}: ${t.getMessage}")
          null
      }

    val routeInfo = entries.split(',').map(startRoute)
    println(s"Started routes in $symName $version")
    routeInfo
  }

  def startExtensions(initInfo: InitInfo) = {
    import initInfo.{jarPath, symName, version, entries}
    def startExtension(extension: String): (String, String, Class[_]) = {
      try {
        val clazz = Class.forName(extension + '$', true, getClass.getClassLoader)
        println(s"Started extension $extension in $symName $version")
        (symName, version, clazz)
      } catch {
        case e: Exception =>
          val t = getRootCause(e)
          println(s"Can't load extension $extension.\n" +
          s"Cube: $symName $version\n" +
          s"Path: $jarPath\n" +
          s"${t.getClass.getName}: ${t.getMessage}")
        null
      }
    }
    entries.split(',').map(startExtension)
  }

  private[this] def parseOptions(options: String) = 
    options.split(';').map { nv =>
      val eqIdx = nv.indexOf('=')
      val name = nv.substring(0, eqIdx)
      val value = nv.substring(eqIdx + 1)
      name -> value        
    }      

  
  private[this] def getRootCause(e: Exception) = {
    var t : Throwable = e
    var cause = e.getCause
    while (cause != null) {
      t = cause
      cause = t.getCause
    }
    t
  }
}
