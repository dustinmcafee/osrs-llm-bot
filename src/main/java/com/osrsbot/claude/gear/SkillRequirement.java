package com.osrsbot.claude.gear;

/**
 * A single skill prerequisite for using an item or interacting with an
 * object. The skill code is the short form used in [SKILLS] / annotations
 * (Atk, Str, Def, Rng, Mag, HP, Pray, WC, Mine, Fish, Cook, FM, Craft,
 * Smith, Fletch, Slay, Farm, Con, Hunt, Agi, Thiev, Herb, RC).
 */
public final class SkillRequirement
{
    public final String skill;
    public final int level;

    public SkillRequirement(String skill, int level)
    {
        this.skill = skill;
        this.level = level;
    }

    /** Compact "{Skill}:{N}" form, e.g. "Atk:60", for prompt annotations. */
    public String compact()
    {
        return skill + ":" + level;
    }
}
