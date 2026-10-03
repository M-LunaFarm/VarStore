rootProject.name = "VarStore"

include(
    "varstore-api",
    "varstore-core",
    "varstore-postgres",
    "varstore-paper",
    "varstore-tools",
    "varstore-testkit",
    "varstore-testkit-paper",
    "varstore-cache",
    "varstore-codec",
    "varstore-placeholderapi",
    "varstore-skript",
)

include(
    "examples:preferences",
    "examples:rewards",
    "examples:quests",
    "examples:structured",
)
