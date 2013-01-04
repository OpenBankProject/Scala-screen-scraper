package bootstrap.liftweb

import net.liftweb.common._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import com.tesobe.obp_importer.lib.Importer

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot extends Loggable {
  def boot {
    // set up email auth
    Mailer.authenticator = for {
      user <- Props.get("mail.user")
      pass <- Props.get("mail.password")
    } yield new Authenticator {
      override def getPasswordAuthentication =
        new PasswordAuthentication(user, pass)
    }

    // start importer
    Schedule.schedule(Importer.doImports _, 5 seconds)
    logger.info("boot complete")
  }
}
