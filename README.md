This project contains screenscrapers for Postbank and GLS. Account credentials are read from a directory specific in
the props file (importer.confdir), and used to run a screenscraper. Account credentials are stored in the following format:

{"holder": "The account name",
 "bank": "10010010",
 "account": "0293939020939402",
 "username":"",
 "pindata": "-----BEGIN PGP MESSAGE-----
Version: GnuPG v1.4.10 (GNU/Linux)

SCoisoe9039r0h332rch32r98h32r328hr9sefhlsef8esnfh8l3
-----END PGP MESSAGE-----"}

where pindata contains the encrypted account PIN code that is decrypted by a private key specified in the props file (importer.keyfile).

To unlock the private key, its passphrase should be entered on the index page (e.g. 127.0.0.1:8080).


The screenscraper will run once every hour and post transactions to a secret key protected (set in importer.postSecret in the props file)
 OBP-API tesobe branch api call.
