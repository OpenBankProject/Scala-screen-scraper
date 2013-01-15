package com.tesobe.obp_importer.snippet

import net.liftweb.http._
import net.liftweb.util.Schedule
import net.liftweb.util.Helpers._
import com.tesobe.obp_importer.lib.Importer

class GetPassphrase {
  def render = {
    if (Importer.passphrase == "") {
      "type=text" #> SHtml.password("", Importer.passphrase = _) &
        "type=submit" #> SHtml.submit("Send", () => {
          Schedule.schedule(Importer.doImports _, 1 seconds)
          S.notice("Importer started")
        })
    } else {
      "div *" #> "The pass phrase is already set."
    }
  }
}