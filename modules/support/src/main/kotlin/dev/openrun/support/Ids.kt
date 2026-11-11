package dev.openrun.support

import java.util.UUID

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
