package com.codeair.mhe.factory.blueprint

/**
 * Shared vocabulary for App Factory blueprint synthesis.
 *
 * Blueprints themselves are designed by the AI from the user's prompt
 * (see [BlueprintSynthesizer] / [BlueprintJsonParser]) — this object no
 * longer contains any per-archetype template specs. What remains:
 *
 * - [Archetype]: optional starting hints the user can pick in the UI. A
 *   hint is passed to the model as inspiration only; it never determines
 *   the output.
 * - [sanitizePackageName]: the package-name rule shared by the parser.
 */
object AppFactoryBlueprintEngine {

    enum class Archetype(val title: String, val description: String) {
        ECOMMERCE("E-Commerce Marketplace", "Catalog, cart, checkout, payments, order tracking"),
        FOOD_DELIVERY("Food & Grocery Delivery", "Restaurant discovery, menus, active order tracking"),
        CHAT_SOCIAL("Chat & Social Messenger", "1:1 and group chats, media sharing, user feed"),
        RIDE_SHARING("Ride Booking & Mobility", "Pick-up / drop-off routing, driver assignment, receipts"),
        TASK_PRODUCTIVITY("Task & Project Manager", "Kanban boards, reminders, priorities, activity logs"),
        FITNESS_TRACKER("Fitness & Workout Tracker", "Exercise routines, sets/reps logging, analytics"),
        NOTES_KNOWLEDGE("Notes & Knowledge Vault", "Markdown notes, notebooks, tags, full-text search"),
        FINANCE_BUDGET("Personal Finance & Budget", "Income & expense tracking, accounts, financial analytics")
    }

    /** Derives a valid Android package name from an app name. */
    fun sanitizePackageName(appName: String): String {
        val clean = appName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val suffix = when {
            clean.length < 3 -> "app$clean"
            clean.first().isDigit() -> "app$clean"
            else -> clean
        }
        return "com.pocketdev.$suffix"
    }
}
