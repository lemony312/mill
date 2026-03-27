package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Tests that touching dependency jar timestamps (e.g. Docker COPY resetting
 * mtimes) does not cause spurious recompilation.
 *
 * Mill's PathRef uses content-based hashing for jars in stable cache locations
 * (Coursier cache, ~/.ivy2/cache/). This makes classLoaderSigHash and resolved
 * dependency signatures immune to timestamp changes.
 */
object StableCacheHashTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    test("touchingCoursierJarsDoesNotRecompile") - integrationTest { tester =>
      import tester.*

      // First compile — resolves dependencies and compiles
      val first = eval("app.compile")
      assert(first.isSuccess)
      assert(first.err.contains("compiling"))

      // Second compile — should be fully cached
      val cached = eval("app.compile")
      assert(cached.isSuccess)
      assert(!cached.err.contains("compiling"))

      // Touch all coursier jars to simulate Docker COPY timestamp reset
      val coursierCache = os.Path(coursier.paths.CoursierPaths.cacheDirectory())
      val jars = os.walk(coursierCache).filter(_.ext == "jar")
      assert(jars.nonEmpty)
      // Set mtime to now to simulate Docker COPY timestamp reset
      val now = java.time.Instant.now()
      val nowFileTime = java.nio.file.attribute.FileTime.from(now)
      for (jar <- jars) {
        java.nio.file.Files.setLastModifiedTime(jar.toNIO, nowFileTime)
      }

      // Compile after touching — should still be cached (no recompilation)
      val afterTouch = eval("app.compile")
      assert(afterTouch.isSuccess)
      assert(!afterTouch.err.contains("compiling"))
    }

    test("sourceChangeStillTriggersRecompilation") - integrationTest { tester =>
      import tester.*

      // First compile
      val first = eval("app.compile")
      assert(first.isSuccess)

      // Cached compile
      val cached = eval("app.compile")
      assert(cached.isSuccess)
      assert(!cached.err.contains("compiling"))

      // Modify source file
      val src = workspacePath / "app/src/app/App.java"
      os.write.append(src, "\n// trigger recompile\n")

      // Should recompile
      val afterChange = eval("app.compile")
      assert(afterChange.isSuccess)
      assert(afterChange.err.contains("compiling"))
    }
  }
}
