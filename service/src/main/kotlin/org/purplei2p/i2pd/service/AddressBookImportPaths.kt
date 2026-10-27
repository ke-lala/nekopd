package org.purplei2p.i2pd.service

import android.content.Context
import java.io.File

/** App-owned imports stay outside i2pd's cache, which the running router rewrites on shutdown. */
object AddressBookImportPaths {
    private const val IMPORT_DIRECTORY = "i2pd-addressbook"
    private const val IMPORTED_MAPPINGS = "imported.csv"

    fun mappings(context: Context): File = File(File(context.filesDir, IMPORT_DIRECTORY), IMPORTED_MAPPINGS)
}
