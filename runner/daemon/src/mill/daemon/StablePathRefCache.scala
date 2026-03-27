package mill.daemon

import mill.api.PathRef

/**
 * Persistent disk cache mapping (path, size) -> content-based PathRef signature.
 *
 * Jars in stable cache locations (coursier, ivy2) are immutable once downloaded.
 * Their content never changes, but Docker COPY resets timestamps, breaking
 * Mill's quick (mtime-based) PathRef signatures. This cache stores content-based
 * signatures keyed by (path, size), making classLoaderSigHash immune to
 * timestamp changes while avoiding the cost of re-hashing jars on every startup.
 *
 * Thread safety: This class is NOT thread-safe. It is designed to be used from
 * a single thread during Mill bootstrap (MillBuildBootstrap). Do not share
 * instances across threads without external synchronization.
 */
class StablePathRefCache(cachePath: os.Path) {
  // Map from absolute path string to (fileSize, contentSig)
  private var cache: Map[String, (Long, Int)] = load()
  private var dirty: Boolean = false

  private def load(): Map[String, (Long, Int)] = {
    if (os.exists(cachePath)) {
      try {
        val loaded = upickle.read[Map[String, (Long, Int)]](os.read(cachePath))
        // Evict entries for paths that no longer exist to prevent unbounded growth
        val (valid, stale) = loaded.partition { case (p, _) => os.exists(os.Path(p)) }
        if (stale.nonEmpty) dirty = true
        valid
      } catch { case _: Exception => Map.empty }
    } else Map.empty
  }

  /**
   * Returns the content-based signature for the given path. If the path+size
   * match a cached entry, returns the cached sig instantly. Otherwise computes
   * the MD5-based content hash and caches it.
   */
  def getOrCompute(path: os.Path): Int = {
    val key = path.toString
    val size = try os.size(path) catch { case _: Exception => return PathRef(path, quick = true).sig }
    cache.get(key) match {
      case Some((cachedSize, sig)) if cachedSize == size => sig
      case _ =>
        val sig = PathRef(path, quick = false).sig
        cache = cache.updated(key, (size, sig))
        dirty = true
        sig
    }
  }

  def save(): Unit = if (dirty) {
    os.write.over(cachePath, upickle.write(cache), createFolders = true)
    dirty = false
  }
}

object StablePathRefCache {
  private[daemon] def isInStableCache(p: os.Path): Boolean =
    mill.util.Jvm.isInStableCache(p)
}
