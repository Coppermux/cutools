package com.coppermux.cutools.core.error

/**
 * Storage-specific error (Storage domain command execution, usage/quota
 * response parsing, or storage-model processing failure). Retains the
 * protocol/terminal error and is never silently swallowed.
 */
class StorageException(message: String, cause: Throwable? = null, code: String = "STORAGE_ERROR") :
    CdpException(message, cause) {
    override val category = Category.STORAGE
    override val code: String = code
}