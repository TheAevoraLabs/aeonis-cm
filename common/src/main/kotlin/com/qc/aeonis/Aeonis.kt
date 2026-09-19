package com.qc.aeonis

import org.slf4j.LoggerFactory

object Aeonis {
    const val MOD_ID = "aeonis"
    private val LOGGER = LoggerFactory.getLogger(MOD_ID)

    fun init() {
        LOGGER.info("Initializing Aeonis Common")
        // Shared registry calls will go here
    }
}
