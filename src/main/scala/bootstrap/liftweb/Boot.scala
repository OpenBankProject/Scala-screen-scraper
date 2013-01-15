package bootstrap.liftweb

import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import net.liftweb.http.LiftRules

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot extends Loggable {
  def boot {
    // configure logging for HtmlUnit
    System.setProperty("org.apache.commons.logging.Log",
      "org.apache.commons.logging.impl.Log4JLogger");

    // set up email auth
    Mailer.authenticator = for {
      user <- Props.get("mail.user")
      pass <- Props.get("mail.password")
    } yield new Authenticator {
      override def getPasswordAuthentication =
        new PasswordAuthentication(user, pass)
    }

    // ask for the passphrase
    val keyfile = Props.get("importer.keyfile") getOrElse {
      logger.warn("private key location (importer.keyfile) not set in props file!")
      "key.gpg"
    }

    LiftRules.addToPackages("com.tesobe.obp_importer")
  }
}
