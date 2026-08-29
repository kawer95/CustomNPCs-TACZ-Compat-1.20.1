package com.arxyt.customnpcstaczcompat;

import noppes.npcs.entity.EntityNPCInterface;

/** The public CNPC 1.20.1 crawl animation value. */
public final class NpcCrawlState {
    public static final int CRAWL_ANIMATION = 7;

    private NpcCrawlState() { }

    public static boolean isCrawling(EntityNPCInterface npc) {
        return npc != null && npc.currentAnimation == CRAWL_ANIMATION;
    }
}
