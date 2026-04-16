resolvers += "GitHub Packages" at "https://maven.pkg.github.com/magaransoft/nomad"
credentials ++= sys.env.get("GH_ACTIONS_READ_PACKAGES_TOKEN").map { token =>
  Credentials("GitHub Package Registry", "maven.pkg.github.com", "_", token)
}.toSeq

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")
