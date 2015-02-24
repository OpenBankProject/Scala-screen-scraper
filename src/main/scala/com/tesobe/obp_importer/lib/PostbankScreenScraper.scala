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


class PostbankScreenScraper extends HtmlUnit with Loggable with CryptoHandler {
  // NB. This used to be an object instead of a class, but then the
  //  call to close() makes it unusable afterwards

  implicit val driver = HtmlUnit.webDriver

  def getTransactions(account: AccountConfig): Seq[OBPTransaction] = {
    /*! First, decrypt the encrypted login secret for this account. */
    val pinBox: Box[String] = decryptPin(account.pindata, Importer.passphrase)
    /*! If successful, start the browser and get the CSV file. */
    val csvDataBox: Box[String] = pinBox.flatMap(pin => {
      tryo {
        /*! Go to login page of Postbank */
        val loginPage = "https://banking.postbank.de/rai/login"
        logger.debug("going to " + loginPage)
        goTo(loginPage)

        /*! Fill in the login details */
        logger.debug("fill in login details")
        textField("id1").value = account.account
        // NB. There's no passwordField, so we do this "manually"
        val pwField = IdQuery("pin-number").webElement
        pwField.clear()
        pwField.sendKeys(pin)
        click on XPathQuery("""//button[@type='submit']""")

        /*! Open the account details */
        logger.debug("open account details")
        val alleUmsaetzeXpath = """//div[@id='nav-global']/ul/li[2]/a"""
        click on XPathQuery(alleUmsaetzeXpath)
        Thread.sleep(2000)

        /*! Download the CSV file. Since downloading with Selenium is a
            pain, we create a dispatch request, insert the cookie data from
            Postbank and query the already-known URL. */
        logger.debug("download CSV file")
        //the link to download the CSV file
        val CSVXpath = """//div[@class='tbl-sales-options-bd']/ul/li[3]/a"""
        val csvUrl = XPathQuery(CSVXpath).element.attribute("href").getOrElse("")
        val browserCookie = cookie("JSESSIONID")
        val dlCookie = new Cookie(browserCookie.domain, browserCookie.name, browserCookie.value, browserCookie.path, 10000, browserCookie.secure)
        // see <http://dispatch.databinder.net/Bargaining+with+promises.html>
        val dlReq = url(csvUrl).addCookie(dlCookie)
        val request = Http(dlReq OK as.Bytes)
        val data = for (d <- request) yield new String(d, "WINDOWS-1252")
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
      /* Extract the header information. Header looks like:
       * Umsatzauskunft - gebuchte Umsätze
	   * Name;MUSIC PICTURES LIMITED
       * BLZ;10010010
       * Kontonummer;0580591101
       * IBAN;DE40100100100580591101
       * Aktueller Kontostand;***
       * Summe vorgemerkter Umsätze;***
       */
      val reader = new CSVReader(new StringReader(csvData), ';')
      val lines = JavaConversions.collectionAsScalaIterable(reader.readAll).toList
      val csvTitle = lines(0)(0)
      logger.debug("csvTitle: " + csvTitle)
      val myName = lines(1)(1)
      logger.debug("myName: " + myName)
      val myBankCode = lines(2)(1)
      logger.debug("myBankCode: " + myBankCode)
      val myAccountNumber = lines(3)(1)
      logger.debug("myAccountNumber: " + myAccountNumber)
      val myIBAN = lines(4)(1)
      logger.debug("myIBAN: " + myIBAN)
      val currentBalance = computeAmount(lines(5)(1)).amount
      logger.debug("currentBalance: " + currentBalance)
      val myBank = OBPBank(
        IBAN = myIBAN,
        national_identifier = account.bank,
        name = "POSTBANK")
      val myAccount = OBPAccount(
        holder = account.holder,
        number = myAccountNumber,
        kind = "current",
        bank = myBank)
      /*! Loop over the remaining lines of the CSV and create
          OBPTransactions from them. */
      lines.slice(9, lines.size).map(line => {
        csvLineToTransaction(line.toList, myAccount)
      }).flatten
    }).getOrElse(Nil)
  }

  private def computeAmount(n: String): OBPAmount = {
    // we process this as a string 1. in order to preserve the number
    //  of digits and 2. because the server expects it like this
    def processNumber(n: String): String = {
      n.trim().filterNot(_ == '.').replace(",", ".")
    }
    //the currency is by default "EUR" because the original value is the "€" sign
    //TODO: implement a method to return the ISO 2417 currency code from the currency sign
    OBPAmount("EUR", processNumber(n.take(n.size - 1)))
  }

  private def formatDate(s: String): String = {
    // in: "01.11.2012", out: "2012-11-01T00:00:00.001Z")
    s.split('.').reverse.mkString("-") + "T00:00:00.001Z"
  }

  def csvLineToTransaction(line: List[String], myAccount: OBPAccount): Box[OBPTransaction] = {
    /* a line looks like
     * "Buchungstag";"Wertstellung";"Umsatzart";"Buchungsdetails";"Auftraggeber";"Empfänger";"Betrag ()";"Saldo ()"
     * "04.01.2013";"04.01.2013";"Lastschrift";"RECHNUNGSNR.  R0002558085 REGISTRATION ROBOT - SERVER ";"MUSIC PICTURES LIMITED";"HETZNER ONLINE AG";"-123,45 €";"12.345,67 €"
     */
    line match {
      case day1 :: day2 :: transactType :: description ::
        sender :: receiver :: value :: balance :: Nil =>
        // collect all information about the other account involved in this transaction
        val otherName =
          if (sender == myAccount.holder) receiver
          else sender

        val otherAccount = OBPAccount(
          holder = otherName,
          number = "",
          kind = "",
          bank = OBPBank("", "", ""))
        // collect all monetary information about this transaction
        val details = OBPDetails(
          kind = transactType,
          posted = OBPDate(formatDate(day1)),
          completed = OBPDate(formatDate(day2)),
          new_balance = computeAmount(balance),
          value = computeAmount(value),
          label = description)
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
