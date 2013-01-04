package com.tesobe.obp_importer.lib;

import org.openqa.selenium.WebDriver
import org.scalatest.time._
import org.scalatest.selenium._
import net.liftweb.util.Helpers._
import net.liftweb.common._
import net.liftweb.util.Props
import com.tesobe.obp_importer.model._
import dispatch._
import com.ning.http.client.Cookie

object PostbankScreenScraper extends Firefox with Loggable {

  def getTransactions(account: AccountConfig): Seq[OBPTransaction] = {
    val csvDataBox = tryo {
      /*! Go to login page of Postbank */
      val loginPage = "https://banking.postbank.de/rai/login"
      logger.debug("going to " + loginPage)
      goTo(loginPage)
      logger.debug("h1: " + findAll(XPathQuery("//h1")).map(_.text).mkString(", "))

      /*! Fill in the login details */
      logger.debug("fill in login details")
      textField("id1").value = account.account
      // NB. There's no passwordField, so we do this "manually"
      val pwField = IdQuery("pin-number").webElement
      pwField.clear()
      pwField.sendKeys("***") // TODO: real PIN
      click on XPathQuery("""//button[@type='submit']""")
      logger.debug("h1: " + findAll(XPathQuery("//h1")).map(_.text).mkString(", "))

      /*! Open the account details */
      logger.debug("open account details")
      val alleUmsaetzeXpath = "//div[@class='accordion-panel-cn']//div[@class='table-ft']//a"
      click on XPathQuery(alleUmsaetzeXpath)
      logger.debug("opened account details")
      Thread.sleep(2000)

      /*! Download the CSV file. Since downloading with Selenium is a
          pain, we create a dispatch request, insert the cookie data from
          Postbank and query the already-known URL */
      logger.debug("download CSV file")
      val csvUrl = "https://banking.postbank.de/rai/?wicket:interface=:3:umsatzauskunftContainer:umsatzauskunftpanel:panel:form:umsatzanzeigeGiro:umsatzaktionen:umsatzanzeigeUndFilterungDownloadlinksPanel:csvHerunterladen::IResourceListener::"
      val browserCookie = cookie("JSESSIONID")
      val dlCookie = new Cookie(browserCookie.domain, browserCookie.name, browserCookie.value, browserCookie.path, 10000, browserCookie.secure)
      // see <http://dispatch.databinder.net/Bargaining+with+promises.html>
      val dlReq = url(csvUrl).addCookie(dlCookie)
      val request = Http(dlReq OK as.Bytes)
      val data = for (d <- request) yield new String(d, "ISO-8859-15")
      data()
    }
    close()
    csvDataBox match {
      case Full(data) =>
        logger.info("got CSV file")
      case Failure(msg, ex, _) =>
        logger.error("error: " + msg)
      case _ =>
        logger.error("got an error")
    }
    csvDataBox.map(csvData => {
      // TODO
    })
    Nil
  }

}
