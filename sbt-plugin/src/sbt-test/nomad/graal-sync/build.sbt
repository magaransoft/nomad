lazy val root = (project in file("."))
  .settings(
    name := "test-graal-sync",
    version := "0.1.0",
    scalaVersion := "3.8.2",
    nomadGraalSync := true,
    // Verify the generated resource-config.json is valid JSON with correct formatting
    TaskKey[Unit]("checkResourceConfig") := {
      val configFile = nomadGraalResourceConfigFile.value
      assert(configFile.exists(), s"resource-config.json does not exist at: $configFile")
      val content = IO.read(configFile)
      // Check that it's properly formatted (no double newlines, consistent indentation)
      assert(!content.contains("\n\n\n"), "resource-config.json has excessive blank lines")
      // Verify pattern is present
      assert(content.contains("migrations/.*\\\\.sql"), s"Missing migrations pattern in:\n$content")
      // Verify proper JSON structure with indentation
      val lines = content.linesIterator.toVector
      val patternLines = lines.filter(_.contains("\"pattern\""))
      assert(patternLines.nonEmpty, "No pattern lines found")
      // All pattern lines should have same indentation
      val indents = patternLines.map(_.takeWhile(_.isWhitespace))
      assert(indents.distinct.size == 1, s"Inconsistent indentation in pattern lines: $patternLines")
    },
    TaskKey[Unit]("checkTwoPatterns") := {
      val configFile = nomadGraalResourceConfigFile.value
      val content = IO.read(configFile)
      val patternLines = content.linesIterator.filter(_.contains("\"pattern\"")).toVector
      assert(patternLines.size == 2, s"Expected 2 pattern entries, got ${patternLines.size} in:\n$content")
      // Verify comma separation (the closing } before second entry should be followed by comma)
      assert(content.contains("},"), s"Missing comma between entries in:\n$content")
    }
  )
