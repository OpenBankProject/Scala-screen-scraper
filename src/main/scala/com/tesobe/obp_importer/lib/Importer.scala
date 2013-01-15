package com.tesobe.obp_importer.lib

import java.io.File
import scala.io.Source
import dispatch._
import net.liftweb.common._
import net.liftweb.util.Props
import net.liftweb.util.Helpers._
import net.liftweb.json._
import com.tesobe.obp_importer.model._

object Importer extends Loggable {
  var passphrase = ""

  def doImports = {
    logger.info("running importer")

    /*! Get a list of all files in the configured directory. */
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

    /*! Loop over all files in the configured directory, parse their as JSON
        and try to convert it into an instance of AccountConfig case class.
        Skip files that could not be converted this way. */
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

    /*! Loop over all AccountConfig objects that could be read from the
        JSON files and see whether we have an appropriate method to get
        a list of transactions for the bank code in that AccountConfig.
        This could be, say, a screen scraper or an HBCI connector.
        Skip configurations that have no matching method. */
    val usableAccounts = configs.map(account => {
      // associate the correct getTransactions method to each account
      val getTransactionsFun: Box[AccountConfig => Seq[OBPTransaction]] = account.bank match {
        case "10010010" | // Postbank Berlin
          // and others ...
          "20010020" => // Postbank Hamburg
          logger.debug("selecting Postbank screen scraper for " + account.toShortString)
          Full(PostbankScreenScraper.getTransactions _)
        case "43060967" =>
          logger.info("selecting GLS screen scraper for " + account.toShortString)
          Full(GlsScreenScraper.getTransactions _)
        case _ =>
          logger.warn("no handler known for " + account.toShortString + ", skipping")
          Empty
      }
      getTransactionsFun.map((account, _))
    }).flatten

    /*! Actually call the determined getTransactions function for each
        AccountConfig. */
    usableAccounts.map {
      case (account, getTransactionsFun) => {
        logger.info("getting transactions for " + account.toShortString)
        // get transactions for this account
        val transactions = tryo {
          getTransactionsFun(account)
        } match {
          case Full(list) =>
            logger.info("received " + list.size + " transactions")
            list
          case Failure(msg, ex, _) =>
            logger.error("failed getting transactions for " +
              account.toShortString + ": " + msg)
            Nil
          case Empty =>
            logger.error("failed getting transactions for " +
              account.toShortString)
            Nil
        }
        /*! Send transactions to the backend. */
        logger.info("inserting transactions for " + account.toShortString)
        val transactionHulls = transactions.map(OBPTransactionWrapper(_))
        val json = compact(render(Extraction.decompose(transactionHulls)))
        // build a request
        val req = url(Props.get("importer.apiurl", "http://localhost:8080") +
          "/api/transactions").POST.
          setHeader("Content-Type", "application/json; charset=utf-8").
          setBody(json.getBytes("UTF-8")) // NB. we have to give the encoding
        val result = Http(req OK as.String).either
        result() match {
          case Left(err) =>
            logger.error("got an error while posting to OBP: " + err.getMessage)
          case Right(res) =>
            logger.info("posting to OBP succeeded")
        }
      }
    }

    sys.exit(0)
  }
}