package com.jev.probe.core.kb

/**
 * Pure matching policy for conversation -> contact lookup.
 *
 * Cross-app identity is never guessed from a display name. When an app package
 * is known, only an exact app-scoped identity or a legacy same-app association
 * may match. A blank app is reserved for manual/global lookups.
 */
object ContactIdentityMatcher {
    fun findConversationContact(
        contacts: List<Contact>,
        title: String,
        app: String,
        normalize: (String?) -> String
    ): Contact? {
        val want = normalize(title)
        if (want.isEmpty()) return null

        if (app.isNotBlank()) {
            contacts.firstOrNull { contact ->
                contact.identities.any { identity ->
                    identity.app == app &&
                        identity.scope.isBlank() &&
                        normalize(identity.title) == want
                }
            }?.let { return it }

            // Backward compatibility for pre-identity data: the old app list is
            // still app-scoped, so it cannot leak a same-named person across apps.
            return contacts.firstOrNull { contact ->
                app in contact.apps &&
                    (normalize(contact.name) == want ||
                        contact.aliases.any { normalize(it) == want })
            }
        }

        return contacts.firstOrNull { contact ->
            normalize(contact.name) == want ||
                contact.aliases.any { normalize(it) == want }
        }
    }
}
