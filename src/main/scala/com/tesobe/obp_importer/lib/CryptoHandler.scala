package com.tesobe.obp_importer.lib

import java.io._
import scala.sys.process._
import net.liftweb.util.Helpers._
import net.liftweb.util.Props
import net.liftweb.common._

/**
 * Provides functionality to decrypt a given encrypted password.
 */
trait CryptoHandler extends Loggable {
  def decryptPin(data: String, passphrase: String): Box[String] = {
    tryo {
      val p = new PGPUtils
      /*! Create an input stream for the encrypted data. */
      val in = new ByteArrayInputStream(data.getBytes())
      /*! Now create an input stream for the private key data. This key
          is expected to live in a file that's specified in the props file.
          We can obtain such a file by saying
          `gpg --export-secret-keys {key-id} > key.gpg` */
      val keyIn = new FileInputStream(Props.get("importer.keyfile", {
        logger.warn("private key location (importer.keyfile) not set in props file!")
        "key.gpg"
      }))
      /*! Create a stream where the output data (the decrypted data)
          will go to. */
      val out = new ByteArrayOutputStream

      /*! Decrypt the data and return the whitespace-trimmed string */
      PGPUtils.decryptFile(in, out, keyIn, passphrase.toCharArray)
      out.toString.trim
    }
  }
}