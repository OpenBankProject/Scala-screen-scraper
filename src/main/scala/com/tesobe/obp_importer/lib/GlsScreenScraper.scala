package com.tesobe.obp_importer.lib;

import java.io.StringReader
import scala.collection.JavaConversions
import org.openqa.selenium.WebDriver
import org.scalatest.time._
import org.scalatest.selenium._
import net.liftweb.util.Helpers._
import net.liftweb.common._
import net.liftweb.util.Props
import dispatch._
import com.ning.http.client.Cookie
import au.com.bytecode.opencsv.CSVReader
import com.tesobe.obp_importer.model._
import bootstrap.liftweb.Boot
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import com.gargoylesoftware.htmlunit.BrowserVersion

class GlsScreenScraper extends HtmlUnit with Loggable with CryptoHandler {
  //due to an update in the GLS website, we have to emulate a proper browser
  implicit val driver = new HtmlUnitDriver(BrowserVersion.CHROME)

  // NB. This used to be an object instead of a class, but then the
  //  call to close() makes it unusable afterwards

  def getTransactions(account: AccountConfig): Seq[OBPTransaction] = {
    /*! First, decrypt the encrypted login secret for this account. */
    val pinBox: Box[String] = decryptPin(account.pindata, Importer.passphrase)
    /*! If successful, start the browser and get the CSV file. */
    val csvDataBox: Box[String] = pinBox.flatMap(pin => {
      tryo {
        /*! Go to login page of GLS */
        val loginPage = "https://internetbanking.gad.de/ptlweb/WebPortal?bankid=4967"
        logger.debug("going to " + loginPage)
        goTo(loginPage)

        /*! Fill in the login details */
        logger.debug("fill in login details")
        textField("vrkennungalias").value = account.username
        // NB. There's no passwordField, so we do this "manually"
        val pwField = IdQuery("pin").webElement
        pwField.clear()
        pwField.sendKeys(pin)
        // NB. For modern browsers, you'll get <button type="submit" ... /> from GLS
        clickOn("button_login")

        /*! Open the account details */
        logger.debug("open account details")
        val alleUmsaetzeXpath = "//a[contains(., '" + account.account + "')]"
        click on XPathQuery(alleUmsaetzeXpath)
        Thread.sleep(2000)

        /*! Download the CSV file. Since downloading with Selenium is a
            pain, we create a dispatch request, insert the cookie data from
            GLS and query the already-known URL. */
        logger.debug("download CSV file")
        val csvUrl = "https://www.gls-online-filiale.de" + XPathQuery("//form[@name='form1']").
          findElement.flatMap(_.attribute("action")).getOrElse("#")
        val browserCookie = cookie("JSESSIONID")
        val lbCookie = cookie("LBSESSION")
        val dlCookie = new Cookie(browserCookie.domain, browserCookie.name, browserCookie.value, browserCookie.path, 10000, browserCookie.secure)
        val dlCookie2 = new Cookie(lbCookie.domain, lbCookie.name, lbCookie.value, lbCookie.path, 10000, lbCookie.secure)
        // see <http://dispatch.databinder.net/Bargaining+with+promises.html>
        val dlReq = url(csvUrl)
          .addCookie(dlCookie)
          .addCookie(dlCookie2)
          .setHeader("Content-Type", "application/x-www-form-urlencoded")
          // had to use a "standard" user agent
          .setHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/34.0.1847.116 Chrome/34.0.1847.116 Safari/537.36")
          .addParameter("idKontoGewaehlt","0")
          .addParameter("nrRadioBoxSelektion","1")
          .addParameter("idZeitraumGewaehlt","0")
          .addParameter("umsatzSucheVO.betrBetragVon","")
          .addParameter("umsatzSucheVO.betrBetragBis","")
          .addParameter("holeUmsaetzeInVO.txtSuch","")
          .addParameter("kzCollapsed","j")
          .addParameter("tableSort_Table_1","")
          .addParameter("event___export","Exportieren")
          .POST

        val response = Http(dlReq OK as.String)
        val data = for (body <- response) yield {
            body
          }
        data()
      }
    })
    close()
    csvDataBox match {
      case Full(data) =>
        logger.info("got CSV file")
      case Failure(msg, ex, _) =>
        logger.error("error: " + msg)
      case _ =>
        logger.error("got an error")
    }

    /*! Parse the CSV file. */
    csvDataBox.map(csvData => {
      val reader = new CSVReader(new StringReader(csvData), ';')
      val lines = JavaConversions.collectionAsScalaIterable(reader.readAll).toList
      val myBank = OBPBank(
        IBAN = "", // TODO get IBAN
        national_identifier = account.bank,
        name = "GLS")
      val myAccount = OBPAccount(
        holder = account.holder,
        number = account.account,
        kind = "current",
        bank = myBank)
      /*! Loop over the remaining lines of the CSV and create
          OBPTransactions from them. */
      lines.slice(1, lines.size).map(line => {
        csvLineToTransaction(line.toList, myAccount)
      }).flatten
    }).getOrElse(Nil)
  }

  private def computeAmount(n: String, c: String): OBPAmount = {
    // we process this as a string 1. in order to preserve the number
    //  of digits and 2. because the server expects it like this
    def processNumber(n: String): String = {
      n.trim().filterNot(_ == '.').replace(",", ".")
    }
    OBPAmount(c, processNumber(n))
  }

  private def formatDate(s: String): String = {
    // in: "01.11.2012", out: "2012-11-01T00:00:00.001Z")
    s.split('.').reverse.mkString("-") + "T00:00:00.001Z"
  }

  def csvLineToTransaction(line: List[String], myAccount: OBPAccount): Box[OBPTransaction] = {
    /* a line looks like
     * Kontonummer;Buchungstag;Wertstellung;Auftraggeber/Empfänger;Buchungstext;VWZ1;VWZ2;VWZ3;VWZ4;VWZ5;VWZ6;VWZ7;VWZ8;VWZ9;VWZ10;VWZ11;VWZ12;VWZ13;VWZ14;Betrag;Kontostand;Währung
     * 114***;03.01.2013;03.01.2013;HETZNER ONLINE AG;"Einzugsermächtig.-lastschr.";RECHNUNGSNR. ***;REGISTRATION ROBOT - SERVER;;;;;;;;;;;;;-12,34;12.345,67;EUR
     */
    line match {
      case accountNo :: day1 :: day2 :: other :: transactType ::
        desc1 :: desc2 :: desc3 :: desc4 :: desc5 :: desc6 :: desc7 ::
        desc8 :: desc9 :: desc10 :: desc11 :: desc12 :: desc13 :: desc14 ::
        value :: balance :: currency :: Nil =>
        // collect all information about the other account involved in this transaction
        val otherAccount = OBPAccount(
          holder = other,
          number = "",
          kind = "",
          bank = OBPBank("", "", ""))
        // collect all monetary information about this transaction
        val description = (desc1 :: desc2 :: desc3 :: desc4 :: desc5 :: desc6 :: desc7 ::
          desc8 :: desc9 :: desc10 :: desc11 :: desc12 :: desc13 :: desc14 :: Nil) mkString "\n" trim
        val details = OBPDetails(
          type_en = "",
          type_de = transactType,
          posted = OBPDate(formatDate(day1)),
          completed = OBPDate(formatDate(day2)),
          new_balance = computeAmount(balance, currency),
          value = computeAmount(value, currency),
          label = description,
          other_data = "")
        val t = OBPTransaction(
          this_account = myAccount,
          other_account = otherAccount,
          details = details)
        logger.debug(t)
        Full(t)
      case _ =>
        logger.warn("line '" + line + "' is not well-formed, skipping")
        Empty
    }
  }
}
