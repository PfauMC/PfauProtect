package io.pfaumc.pfauprotect.model
// Ids are written into every stored record, so a value is never reused: dropping a cause leaves a
// permanent hole rather than freeing its number for the next one.
enum class Cause(val id: Int) {
    PICKUP(0x00),
    DROP_FROM_HAND(0x01),
    DROP_FROM_MENU(0x02),
    DROP_MENU_CLOSE(0x03),
    DROP_ON_DISCONNECT(0x04),
    INVENTORY_OVERFLOW_DROP(0x05),
    CURSOR_PLACE(0x06),
    CURSOR_TAKE(0x07),
    CURSOR_SWAP(0x08),
    QUICK_MOVE(0x09),
    HOTBAR_SWAP(0x0A),
    OFFHAND_SWAP(0x0B),
    COLLECT_ALL(0x0C),
    QUICK_CRAFT_DISTRIBUTE(0x0D),
    EQUIP_ARMOR(0x0E),
    UNEQUIP_ARMOR(0x0F),

    // A class larger than its sixteen slots continues in the reserve range instead of shifting the
    // classes above it: 0x00 spills into 0x90, 0x10 into 0xA0, 0x30 into 0xB0, 0x40 into 0xC0.
    MENU_CLOSE_RETURN(0x90),
    DEATH_DROP(0x91),
    DEATH_DESTROY_VANISHING(0x92),
    TOTEM_CONSUME(0x93),
    INVENTORY_LOAD(0x94),
    ITEM_VANISHED(0x95),
    // What a rollback put back, in either plane: a block returned to the state a row says it had, a
    // slot refilled or emptied, an item taken back from whoever carried it off. The actor is whoever
    // ran the rollback.
    ROLLBACK(0x96),
    // The entity plane's agents where no block cause fits: a mob, a pet or a stand killed, a frame, a
    // painting, a boat or a cart broken, by a hit, an arrow, fire or a command.
    ENTITY_KILLED(0x97),
    ENTITY_BROKEN(0x98),
    // A young animal two parents had because a player fed them.
    MOB_BRED(0x99),
    // A player changed an entity by hand — named, sheared, dyed, turned a frame — or led it away on a
    // lead, a saddle or a boat.
    ENTITY_CHANGED(0x9A),
    ENTITY_LED(0x9B),
    // A player killed by another, directly or by what the other set going.
    PLAYER_KILLED(0x9C),
    // A liquid a player let out running on into air: where his water or lava went. A rollback walks past
    // it — the liquid goes by itself once its source is taken back — and a lookup shows the spread.
    BLK_LIQUID_FLOW(0x9D),
    // What a purge left of the rows it deleted: each holder's balance of an item at the cutoff, from or
    // into the void, so every balance after it still adds up.
    PURGE_OPENING(0x9E),
    // A block another plugin changed and reported through the API, on whoever it named.
    BLK_PLUGIN(0x9F),

    CONTAINER_ADD(0x10),
    CONTAINER_REMOVE(0x11),
    CONTAINER_BREAK_DROP(0x12),
    CONTAINER_BREAK_PACK(0x13),
    CONTAINER_PLACE_UNPACK(0x14),
    HOPPER_PULL_CONTAINER(0x15),
    HOPPER_PULL_GROUND(0x16),
    HOPPER_PUSH(0x17),
    HOPPER_MINECART_PULL(0x18),
    DROPPER_PUSH(0x19),
    DROPPER_EJECT(0x1A),
    DISPENSER_EJECT(0x1B),
    DISPENSER_BEHAVIOR(0x1C),
    CRAFTER_CONSUME(0x1D),
    CRAFTER_EMIT(0x1E),
    FURNACE_FUEL_CONSUME(0x1F),

    FURNACE_FUEL_REMAINDER(0xA0),
    BREWING_INGREDIENT_CONSUME(0xA1),
    BREWING_FUEL_CONSUME(0xA2),
    COMPOSTER_CONSUME(0xA3),
    COMPOSTER_BONEMEAL(0xA4),
    CAMPFIRE_COOK_DROP(0xA5),
    BEEHIVE_HARVEST(0xA6),
    BRUSHABLE_REVEAL(0xA7),
    // A container item given the name its contents are filed under.
    CONTAINER_NAMED(0xA8),

    ITEM_SPAWN(0x20),
    ITEM_MERGE(0x21),
    ITEM_DESPAWN(0x22),
    ITEM_PICKUP_BY_MOB(0x23),
    ITEM_PICKUP_BY_MOB_INV(0x24),
    ITEM_DESTROY_FIRE(0x25),
    ITEM_DESTROY_CACTUS(0x26),
    ITEM_DESTROY_VOID(0x27),
    ITEM_DESTROY_EXPLOSION(0x28),
    MOB_THROW_ITEM(0x29),

    BLOCK_DROP(0x30),
    MOB_DROP(0x31),
    MOB_EQUIPMENT_DROP(0x32),
    MOB_SPAWN_EQUIPMENT(0x33),
    LOOT_GENERATE(0x34),
    VAULT_REWARD(0x35),
    TRIAL_SPAWNER_REWARD(0x36),
    FISHING_CATCH(0x37),
    TRADE_RESULT(0x38),
    TRADE_PAYMENT(0x39),
    PIGLIN_BARTER(0x3A),
    GIFT_DROP(0x3B),
    SHEARING_DROP(0x3C),
    BLOCK_INTERACT_DROP(0x3D),
    ADVANCEMENT_REWARD(0x3E),
    CREATIVE_SET(0x3F),

    CREATIVE_CLONE(0xB0),
    CREATIVE_PICK(0xB1),
    DIRECT_NEW_ITEM(0xB2),
    // A page of a book and quill written or rewritten: the text is part of what the item is.
    BOOK_EDIT(0xB3),
    // The recipe book laying a recipe's ingredients into the grid out of the inventory.
    RECIPE_BOOK_FILL(0xB4),

    CRAFT_CONSUME(0x40),
    CRAFT_RESULT(0x41),
    CRAFT_REMAINDER(0x42),
    SMELT(0x43),
    BREW(0x44),
    ANVIL_COMBINE(0x45),
    GRINDSTONE(0x46),
    SMITHING_TRANSFORM(0x47),
    SMITHING_TRIM(0x48),
    ENCHANT_APPLY(0x49),
    ENCHANT_LAPIS_CONSUME(0x4A),
    STONECUTTER(0x4B),
    LOOM(0x4C),
    CARTOGRAPHY(0x4D),
    CONSUME_FOOD(0x4E),
    CONSUME_REMAINDER(0x4F),

    DURABILITY_DAMAGE(0xC0),
    DURABILITY_BREAK(0xC1),
    TRANSMUTE_ON_BREAK(0xC2),
    DYE_ITEM(0xC3),
    BOOK_SIGN(0xC4),
    BOOK_COPY(0xC5),
    BANNER_DUPLICATE(0xC6),
    MAP_CLONE(0xC7),
    MAP_SCALE_LOCK(0xC8),
    MAP_FILL(0xC9),

    BLOCK_PLACE(0x50),
    BONEMEAL_USE(0x51),
    BUCKET_FILL(0x52),
    BUCKET_EMPTY(0x53),
    SPAWN_EGG_USE(0x54),
    WAX_APPLY(0x55),
    RECORD_INTO_JUKEBOX(0x56),
    BOOK_ONTO_LECTERN(0x57),
    ITEM_INTO_SINGLE_BLOCK(0x58),
    PLACE_ENTITY_ITEM(0x59),
    EYE_INTO_FRAME(0x5A),
    // Spent on a block or an entity by a path that has no cause of its own: a fire charge, a trial key.
    ITEM_USED(0x5B),
    BOTTLE_FILL(0x5C),
    CAULDRON_WASH(0x5D),
    BEACON_PAYMENT(0x5E),
    BOTTLE_EMPTY(0x5F),

    FEED_MOB(0x60),
    TAME_MOB(0x61),
    EQUIP_MOB(0x62),
    SHEAR_MOB(0x63),
    DYE_MOB(0x64),
    NAME_TAG(0x65),
    LEASH_ATTACH(0x66),
    LEASH_DROP(0x67),
    BUCKET_CAPTURE_MOB(0x68),
    BUCKET_RELEASE_MOB(0x69),
    GIVE_ITEM_TO_MOB(0x6A),
    ARMOR_STAND_SWAP(0x6B),
    MOB_TRANSFORM(0x6C),
    ENTITY_BREAK_DROP(0x6D),
    MOB_EQUIPMENT_LOST(0x6E),

    PROJ_SHOT(0x70),
    PROJ_SHOT_PHANTOM(0x71),
    PROJ_PICKUP(0x72),
    PROJ_DEFLECT_DROP(0x73),
    PROJ_HIT_VOID(0x74),
    PROJ_DESPAWN(0x75),
    TRIDENT_LOYALTY_RETURN(0x76),
    TRIDENT_DROP(0x77),
    EYE_SURVIVE(0x78),
    EYE_SHATTER(0x79),
    THROWN_CONSUMED(0x7A),
    FIREWORK_LAUNCH(0x7B),
    DISPENSED_PROJECTILE(0x7C),

    BUNDLE_INSERT(0x80),
    BUNDLE_EXTRACT(0x81),
    BUNDLE_DUMP(0x82),
    BUNDLE_SPILL_DESTROYED(0x83),
    CROSSBOW_LOAD(0x84),
    CROSSBOW_SHOOT(0x85),

    // The block plane shares this dictionary but names something else: not the interaction that moved
    // an item, but the agent that changed a block. What changed is already in the row's two state
    // fields, so nothing here is a verb.
    BLK_PLAYER_PLACE(0xD0),
    BLK_PLAYER_BREAK(0xD1),
    BLK_TNT(0xD2),
    BLK_CREEPER(0xD3),
    BLK_BED_EXPLOSION(0xD4),
    BLK_RESPAWN_ANCHOR(0xD5),
    BLK_END_CRYSTAL(0xD6),
    BLK_EXPLOSION(0xD7),
    BLK_FIRE_BURN(0xD8),
    BLK_FIRE_SPREAD(0xD9),
    BLK_LIQUID_DESTROY(0xDA),
    BLK_LIQUID_FORM(0xDB),
    BLK_PISTON_EXTEND(0xDC),
    BLK_PISTON_RETRACT(0xDD),
    BLK_FALL_START(0xDE),
    BLK_FALL_LAND(0xDF),

    BLK_GROW(0xE0),
    BLK_BONEMEAL(0xE1),
    BLK_LEAF_DECAY(0xE2),
    BLK_FADE(0xE3),
    BLK_FORM(0xE4),
    BLK_SCULK(0xE5),
    BLK_ENDERMAN(0xE6),
    BLK_WITHER(0xE7),
    BLK_RAVAGER(0xE8),
    BLK_SILVERFISH(0xE9),
    BLK_SNOWMAN(0xEA),
    BLK_FROST_WALKER(0xEB),
    BLK_MOB_GRIEF(0xEC),
    BLK_DISPENSER(0xED),
    BLK_PORTAL_CREATE(0xEE),
    BLK_PORTAL_DESTROY(0xEF),

    // A player pressing a button or a lever, stepping on a pressure plate or through a tripwire: the
    // start of whatever the redstone behind it does next. Outside the block plane's range, which is
    // full, like BLK_SIGN_EDIT.
    BLK_PLAYER_SWITCH(0xCE),
    // The same, set off by an entity. The payload names the entity, and the distance of the player
    // named when that player was only near.
    BLK_ENTITY_SWITCH(0xCF),
    // A block a player changed by hand without placing or breaking anything: a door opened, a log
    // stripped, a path dug, a repeater set, a cake eaten.
    BLK_PLAYER_USE(0xCD),
    // A liquid or powder snow a player's bucket put down or took up.
    BLK_BUCKET(0xCC),
    // Water, waterlogging and water plants a sponge drank, and the sponge turning wet.
    BLK_SPONGE(0xCB),
    // A block a command wrote: /setblock, /fill, /clone, /place. The actor is the player who ran it.
    BLK_COMMAND(0xCA),
    // What a neighbour's change made of a block beside it, which the server rewrites with no event: the
    // sides bars or a fence join, a stair's corner, a wall's height. On whoever changed the neighbour.
    BLK_SHAPE(0xBF),

    CMD_GIVE(0xF0),
    CMD_ITEM_REPLACE(0xF1),
    CMD_ITEM_MODIFY(0xF2),
    CMD_ITEM_COPY(0xF3),
    CMD_LOOT(0xF4),
    CMD_CLONE_CONTAINER(0xF5),
    CMD_CLONE_MOVE_VOID(0xF6),
    CMD_DATA_MERGE(0xF7),
    CMD_SETBLOCK_FILL_FILL(0xF8),
    CMD_SETBLOCK_FILL_VOID(0xF9),
    CMD_SUMMON_ITEMS(0xFA),
    CMD_KILL_ITEM(0xFB),
    CMD_ENCHANT(0xFC),
    CMD_CLEAR(0xFD),

    // Never written, only decoded into. Causes are added without touching the record version, so a
    // row from a newer build carries a number this one has no name for — and the quantity beside it
    // is still readable and still counts towards a balance, which a skipped row would not.
    // The block plane's thirty-two slots are full, so it goes on here.
    BLK_SIGN_EDIT(0xFE),
    UNKNOWN(0xFF),
    ;

    companion object {
        private val BY_ID = arrayOfNulls<Cause>(256).apply {
            for (cause in entries) this[cause.id] = cause
        }

        fun byId(id: Int): Cause? = BY_ID.getOrNull(id)
    }
}
