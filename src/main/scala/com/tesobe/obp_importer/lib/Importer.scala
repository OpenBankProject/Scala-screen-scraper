package com.tesobe.obp_importer.lib

import java.io.File
import scala.io.Source
import net.liftweb.common._
import net.liftweb.util.Props
import net.liftweb.util.Helpers._
import net.liftweb.json._
import com.tesobe.obp_importer.model.AccountConfig
import com.tesobe.obp_importer.model.OBPTransaction

object Importer extends Loggable {
  def doImports = {
    logger.info("running importer")

    // try to get a list of files
    val confDir = Props.get("importer.confdir", "/var/local/obp_importer")
    val files: Seq[File] = tryo {
      new File(confDir).listFiles
    } match {
      case Full(fs) =>
        fs
      case Failure(msg, ex, _) =>
        logger.error("unable to read from config dir: " + msg)
        Nil
      case _ =>
        logger.error("unable to read from config dir: (no error message)")
        Nil
    }
    files.foreach(f => logger.info("found config file: " + f))

    // try to parse json from files
    implicit val formats = DefaultFormats
    val configs: Seq[AccountConfig] = files.map(f => {
      val objBox = tryo {
        val data = Source.fromFile(f).mkString
        val json = parse(data)
        json.extract[AccountConfig]
      }
      objBox match {
        case Failure(msg, ex, _) =>
          logger.error("failed to parse '" + f + "': " + msg)
        case Empty =>
          logger.error("failed to parse '" + f + "'")
        case Full(obj) =>
          logger.debug("successfully parsed '" + f + "'")
      }
      objBox
    }).flatten

    sys.exit(0)
  }
}