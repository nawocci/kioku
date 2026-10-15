package xyz.altaf.kioku

/**
 * Fork switches for upstream UI that Kioku does not ship.
 *
 * These keep upstream code (and its strings) intact behind a flag instead of deleting it,
 * so that merging future Mihon releases stays straightforward.
 */
object ForkFlags {
    /** Mihon's "Support Us" entry, Support Us screen, and the donation campaign dialog. */
    val donationUi: Boolean = false

    /** Mihon's website and social links in the About screen. */
    val upstreamLinks: Boolean = false
}
