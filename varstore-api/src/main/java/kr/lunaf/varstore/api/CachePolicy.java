package kr.lunaf.varstore.api;

/** Cache permission is for display only; primary reads and mutations never use it. */
public enum CachePolicy { DISABLED, DISPLAY_ONLY }
