#!/usr/bin/env python3
"""
HomeClaim World Generation Script (Python)

Erstellt PlotWorld-Konfiguration CLI-only (ohne Ingame-Wizard).

Requirements:
    pip install pyyaml

Usage:
    ./generate-world.py --name MyWorld
    ./generate-world.py -c world-config.json
    ./generate-world.py --name MegaWorld --plots-per-side 1000 --dry-run

Standard-Ausgabe (ohne --worlddata):
    <aktuelles Verzeichnis>/generate-world.py-data/

Erlaubte Startorte ohne --data/--worlddata:
    1) Server-Root (mit server.properties + bukkit.yml)
    2) plugins/HomeClaim/scripts
"""

import argparse
import json
import shutil
import sys
from pathlib import Path
from dataclasses import dataclass, asdict
from datetime import datetime

try:
    import yaml
except ImportError:
    print("Error: pyyaml not installed. Install with: pip install pyyaml")
    sys.exit(1)


@dataclass
class WorldConfig:
    """PlotWorld Konfiguration"""
    name: str
    plot_size: int = 48
    road_width: int = 10
    plot_height: int = 64
    plot_block: str = "GRASS_BLOCK"
    road_block: str = "DARK_PRISMARINE"
    wall_block: str = "DIAMOND_BLOCK"
    accent_block: str = "SMOOTH_QUARTZ_STAIRS"
    plots_per_side: int = 500
    
    # Berechnung
    @property
    def world_size(self) -> int:
        """Gesamtwelt-Größe"""
        return self.plot_size * self.plots_per_side + self.road_width * (self.plots_per_side + 1)
    
    @property
    def total_plots(self) -> int:
        """Gesamtanzahl Plots"""
        return self.plots_per_side ** 2
    
    def validate(self) -> bool:
        """Validiere Konfiguration"""
        if not self.name:
            print("Error: Welt-Name erforderlich")
            return False
        if self.plot_size < 10 or self.plot_size > 1024:
            print(f"Error: Plot-Größe muss zwischen 10 und 1024 liegen (got {self.plot_size})")
            return False
        if self.road_width < 1 or self.road_width > 100:
            print(f"Error: Straßen-Breite muss zwischen 1 und 100 liegen (got {self.road_width})")
            return False
        if self.plots_per_side < 1 or self.plots_per_side > 10000:
            print(f"Error: Plots/Seite muss zwischen 1 und 10000 liegen (got {self.plots_per_side})")
            return False
        return True
    
    def display(self):
        """Zeige Konfiguration an"""
        print("\n" + "="*50)
        print("  HomeClaim World Generation Configuration")
        print("="*50 + "\n")
        
        print("Einstellungen:")
        print(f"  Welt-Name:        {self.name}")
        print(f"  Plot-Größe:       {self.plot_size}x{self.plot_size} Blöcke")
        print(f"  Straßen-Breite:   {self.road_width} Blöcke")
        print(f"  Plot-Höhe:        {self.plot_height} Blöcke")
        print(f"  Plot-Block:       {self.plot_block}")
        print(f"  Straßen-Block:    {self.road_block}")
        print(f"  Mauer-Block:      {self.wall_block}")
        print(f"  Akzent-Block:     {self.accent_block}")
        print(f"  Plots/Seite:      {self.plots_per_side}")
        
        print("\nBerechnungen:")
        print(f"  Gesamtwelt-Größe: {self.world_size}x{self.world_size} Blöcke")
        print(f"  Gesamte Plots:    {self.total_plots}")
        print()


class WorldGenerator:
    """Generiert PlotWorlds"""
    
    def __init__(
        self,
        config: WorldConfig,
    ):
        self.config = config
    
    def create_config_yaml(self, output_path: str = "worlds.yml") -> bool:
        """Erstelle YAML-Konfiguration"""
        cfg = self.config
        output_file = Path(output_path).resolve()
        output_file.parent.mkdir(parents=True, exist_ok=True)
        
        config_data = {
            "worlds": {
                cfg.name.lower().replace(" ", "_"): {
                    "plotSize": cfg.plot_size,
                    "roadWidth": cfg.road_width,
                    "plotHeight": cfg.plot_height,
                    "plotBlock": cfg.plot_block,
                    "roadBlock": cfg.road_block,
                    "wallBlock": cfg.wall_block,
                    "accentBlock": cfg.accent_block,
                    "plotsPerSide": cfg.plots_per_side,
                    "economy": {
                        "use": False
                    },
                    "generated": datetime.now().isoformat()
                }
            }
        }
        
        try:
            with open(output_file, 'w') as f:
                yaml.dump(config_data, f, default_flow_style=False)
            print(f"✓ Konfiguration gespeichert: {output_file}")
            return True
        except Exception as e:
            print(f"❌ Fehler beim Speichern: {e}")
            return False
    
    def create_config_json(self, output_path: str = "worlds.json") -> bool:
        """Erstelle JSON-Konfiguration"""
        cfg = self.config
        output_file = Path(output_path).resolve()
        output_file.parent.mkdir(parents=True, exist_ok=True)
        
        config_data = {
            "worlds": {
                cfg.name.lower().replace(" ", "_"): asdict(cfg)
            }
        }
        
        try:
            with open(output_file, 'w') as f:
                json.dump(config_data, f, indent=2)
            print(f"✓ Konfiguration gespeichert: {output_file}")
            return True
        except Exception as e:
            print(f"❌ Fehler beim Speichern: {e}")
            return False

    def create_world_bundle(self, output_dir: str, data_dir: Path, include_defaults: bool = False) -> bool:
        """
        Create a world-only CLI bundle in a single output directory.
        The structure is scoped to one world and stages a copy-ready server tree.
          <output_dir>/<worldName>/worldgen-options.json
          <output_dir>/<worldName>/<worldName>.toml
          <output_dir>/<worldName>/bukkit-world.yml
          <output_dir>/<worldName>/copy-to-server/plugins/HomeClaim/plot-worlds/<worldName>.toml
          <output_dir>/<worldName>/copy-to-server/plugins/HomeClaim/config.yml
          <output_dir>/<worldName>/copy-to-server/plugins/HomeClaim/config.example.yml
          <output_dir>/<worldName>/copy-to-server/plugins/HomeClaim/sensor-config.toml
          <output_dir>/<worldName>/copy-to-server/plugins/HomeClaim/scripts/generate-world.py
          <output_dir>/<worldName>/copy-to-server/config/HomeClaim.toml
          <output_dir>/<worldName>/copy-to-server/bukkit-world.yml
        """
        cfg = self.config
        world_dir = Path(output_dir).resolve() / cfg.name
        world_dir.mkdir(parents=True, exist_ok=True)

        copy_root = world_dir / "copy-to-server"
        copy_homeclaim_dir = copy_root / "plugins" / "HomeClaim"
        copy_plot_worlds = copy_homeclaim_dir / "plot-worlds"
        copy_scripts_dir = copy_homeclaim_dir / "scripts"
        copy_config_dir = copy_root / "config"
        for folder in (copy_plot_worlds, copy_scripts_dir, copy_config_dir):
            folder.mkdir(parents=True, exist_ok=True)

        homeclaim_toml = render_homeclaim_toml(cfg)
        bukkit_cfg = build_bukkit_generator_patch(cfg)

        staged_files: list[str] = [
            "bukkit-world.yml",
            f"plugins/HomeClaim/plot-worlds/{cfg.name}.toml",
        ]
        if write_staged_config_yaml(copy_homeclaim_dir / "config.yml", cfg, include_defaults):
            staged_files.append("plugins/HomeClaim/config.yml")

        for relative_source, destination in (
            ("config.example.yml", copy_homeclaim_dir / "config.example.yml"),
            ("sensor-config.toml", copy_homeclaim_dir / "sensor-config.toml"),
            ("HomeClaim.toml", copy_config_dir / "HomeClaim.toml"),
            ("scripts/generate-world.py", copy_scripts_dir / "generate-world.py"),
        ):
            if copy_bundled_resource(relative_source, destination):
                staged_files.append(str(destination.relative_to(copy_root)))

        manifest = {
            "generatedAt": datetime.now().isoformat(),
            "worldName": cfg.name,
            "mode": "cli-only",
            "targetDataDir": str(data_dir.resolve()),
            "options": asdict(cfg),
            "files": {
                "bukkit": "bukkit-world.yml",
                "homeclaim": f"{cfg.name}.toml",
            },
            "copyToServer": {
                "root": "copy-to-server",
                "files": sorted(set(staged_files)),
                "defaultsApplied": include_defaults,
            },
        }

        try:
            with open(world_dir / "bukkit-world.yml", "w") as f:
                yaml.dump(bukkit_cfg, f, default_flow_style=False, sort_keys=False)

            with open(world_dir / f"{cfg.name}.toml", "w") as f:
                f.write(homeclaim_toml)

            with open(copy_plot_worlds / f"{cfg.name}.toml", "w") as f:
                f.write(homeclaim_toml)

            with open(copy_root / "bukkit-world.yml", "w") as f:
                yaml.dump(bukkit_cfg, f, default_flow_style=False, sort_keys=False)

            with open(copy_root / "README.txt", "w") as f:
                f.write(
                    "HomeClaim copy-to-server\n"
                    "========================\n\n"
                    "Diesen Unterordner kannst du direkt als Vorlage in einen frischen Serverordner kopieren.\n"
                    "Enthalten sind die benoetigten HomeClaim-Dateien fuer den ersten Start:\n"
                    "- plugins/HomeClaim/plot-worlds/<worldName>.toml\n"
                    "- plugins/HomeClaim/config.yml\n"
                    "- plugins/HomeClaim/config.example.yml\n"
                    "- plugins/HomeClaim/sensor-config.toml\n"
                    "- plugins/HomeClaim/scripts/generate-world.py\n"
                    "- config/HomeClaim.toml\n"
                    "- bukkit-world.yml (Snippet zum Mergen in server-root/bukkit.yml)\n\n"
                    "Empfohlenes Vorgehen:\n"
                    "1) Inhalt von copy-to-server/ in den Server-Root kopieren\n"
                    "2) bukkit-world.yml in die bestehende bukkit.yml uebernehmen\n"
                    "3) Plugin-JAR nach plugins/ kopieren\n"
                    "4) Server starten\n\n"
                    + (
                        f"Zusatz: In plugins/HomeClaim/config.yml wurden fuer '{cfg.name}' bereits Plot-Standardwerte eingetragen.\n"
                        if include_defaults else ""
                    )
                )

            with open(world_dir / "worldgen-options.json", "w") as f:
                json.dump(manifest, f, indent=2)

            with open(world_dir / "README.txt", "w") as f:
                f.write(
                    "HomeClaim PlotWorld bundle\n"
                    "=========================\n\n"
                    "Diese Struktur enthaelt nur Daten fuer genau eine Welt.\n"
                    "Dateien:\n"
                    "- bukkit-world.yml (Snippet fuer worlds.<name>.generator)\n"
                    "- <worldName>.toml (PlotWorld-Config in TOML, fuer plugins/HomeClaim/plot-worlds/)\n"
                    "- worldgen-options.json (generator metadata)\n\n"
                    "copy-to-server/:\n"
                    "- plugins/HomeClaim/plot-worlds/<worldName>.toml\n"
                    "- plugins/HomeClaim/config.yml\n"
                    "- plugins/HomeClaim/config.example.yml\n"
                    "- plugins/HomeClaim/sensor-config.toml\n"
                    "- plugins/HomeClaim/scripts/generate-world.py\n"
                    "- config/HomeClaim.toml\n"
                    "- bukkit-world.yml (zu mergen in server-root/bukkit.yml)\n"
                    "- README.txt (Copy/Install-Hinweise)\n\n"
                    "Hinweis:\n"
                    "Dieses Script startet keinen Minecraft-Server und steuert keinen Setup-Wizard.\n"
                    "Die eigentliche Welt wird vom Server/Plugin erzeugt, sobald die Welt geladen/erstellt wird.\n"
                )

            print(f"✓ Welt-Bundle erstellt: {world_dir}")
            print(f"  - {world_dir / 'bukkit-world.yml'}")
            print(f"  - {world_dir / f'{cfg.name}.toml'}")
            print(f"  - {world_dir / 'worldgen-options.json'}")
            print(f"  - {copy_root}")
            return True
        except Exception as e:
            print(f"❌ Fehler beim Erstellen des Welt-Bundles: {e}")
            return False


def render_homeclaim_toml(cfg: WorldConfig) -> str:
    return (
        f'worldName = "{cfg.name}"\n'
        f"plotSize = {cfg.plot_size}\n"
        f"roadWidth = {cfg.road_width}\n"
        f"plotHeight = {cfg.plot_height}\n"
        f'plotBlock = "{cfg.plot_block}"\n'
        f'roadBlock = "{cfg.road_block}"\n'
        f'wallBlock = "{cfg.wall_block}"\n'
        f'accentBlock = "{cfg.accent_block}"\n'
        f'plotsPerSide = {cfg.plots_per_side}\n'
        f'generator = "HomeClaim"\n'
    )


def build_bukkit_generator_patch(cfg: WorldConfig) -> dict:
    return {"worlds": {cfg.name: {"generator": "HomeClaim"}}}


def build_plotworld_defaults(cfg: WorldConfig) -> dict:
    return {
        "plotSize": cfg.plot_size,
        "roadWidth": cfg.road_width,
        "plotHeight": cfg.plot_height,
        "plotBlock": cfg.plot_block,
        "roadBlock": cfg.road_block,
        "wallBlock": cfg.wall_block,
        "accentBlock": cfg.accent_block,
        "plotsPerSide": cfg.plots_per_side,
        "schema": "default",
    }


def write_staged_config_yaml(destination: Path, cfg: WorldConfig, include_defaults: bool) -> bool:
    """Write the staged config.yml and optionally inject the chosen/default plot values."""
    resources_root = Path(__file__).resolve().parent.parent
    source = resources_root / "config.yml"
    if not source.exists() or not source.is_file():
        return False

    if not include_defaults:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        return True

    loaded = yaml.safe_load(source.read_text(encoding="utf-8")) or {}
    if not isinstance(loaded, dict):
        loaded = {}

    homeclaim_cfg = loaded.get("homeclaim")
    if not isinstance(homeclaim_cfg, dict):
        homeclaim_cfg = {}
        loaded["homeclaim"] = homeclaim_cfg

    plot_worlds = homeclaim_cfg.get("plotWorlds")
    if not isinstance(plot_worlds, dict):
        plot_worlds = {}
        homeclaim_cfg["plotWorlds"] = plot_worlds

    plot_worlds[cfg.name] = build_plotworld_defaults(cfg)

    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        "# Default config with SQLite (persistent storage)\n"
        "# See config.example.yml for PostgreSQL/MySQL variants\n"
        "# Plot defaults for this world were inserted via --use-defaults / --defaults\n"
        + yaml.dump(loaded, default_flow_style=False, sort_keys=False),
        encoding="utf-8",
    )
    return True


def copy_bundled_resource(relative_path: str, destination: Path) -> bool:
    """Copy a bundled resource next to the script into the staged server tree."""
    resources_root = Path(__file__).resolve().parent.parent
    source = resources_root / relative_path
    if not source.exists() or not source.is_file():
        return False
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return True


def vprint(verbose: bool, message: str) -> None:
    if verbose:
        print(f"[verbose] {message}")


def detect_server_root(cwd: Path, verbose: bool = False) -> Path | None:
    if (cwd / "server.properties").exists() and (cwd / "bukkit.yml").exists():
        vprint(verbose, f"Server-Root erkannt über CWD: {cwd}")
        return cwd
    if len(cwd.parts) >= 3 and cwd.parts[-3:] == ("plugins", "HomeClaim", "scripts"):
        candidate = cwd.parents[2]
        if (candidate / "server.properties").exists() and (candidate / "bukkit.yml").exists():
            vprint(verbose, f"Server-Root erkannt über scripts-Pfad: {candidate}")
            return candidate
    vprint(verbose, "Kein Server-Root erkannt.")
    return None


def ensure_apply_allowed(server_root: Path | None, auto_yes: bool) -> Path:
    if server_root:
        return server_root
    print("❌ --apply benötigt einen erkannten Server-Root mit server.properties + bukkit.yml.")
    print("   Starte im Server-Root oder in plugins/HomeClaim/scripts.")
    if not auto_yes and sys.stdin.isatty():
        print("   Alternativ ohne --apply laufen lassen und Dateien manuell kopieren.")
    raise SystemExit(2)


def prompt_yes_no(question: str, default_yes: bool = True, auto_yes: bool = False) -> bool:
    if auto_yes:
        return True
    if not sys.stdin.isatty():
        return default_yes

    suffix = "[Y/n]" if default_yes else "[y/N]"
    while True:
        answer = input(f"{question} {suffix}: ").strip().lower()
        if answer == "":
            return default_yes
        if answer in ("y", "yes", "j", "ja"):
            return True
        if answer in ("n", "no", "nein"):
            return False
        print("Bitte 'y' oder 'n' eingeben.")


def apply_to_server(cfg: WorldConfig, data_dir: Path, server_root: Path, auto_yes: bool, verbose: bool = False) -> bool:
    try:
        plot_worlds_dir = data_dir / "plot-worlds"
        plot_worlds_dir.mkdir(parents=True, exist_ok=True)
        toml_target = plot_worlds_dir / f"{cfg.name}.toml"

        toml_target.write_text(render_homeclaim_toml(cfg), encoding="utf-8")
        print(f"✓ TOML geschrieben: {toml_target}")

        bukkit_yml = server_root / "bukkit.yml"
        if not bukkit_yml.exists():
            print(f"❌ bukkit.yml nicht gefunden: {bukkit_yml}")
            return False

        loaded = yaml.safe_load(bukkit_yml.read_text(encoding="utf-8"))
        bukkit_cfg = loaded if isinstance(loaded, dict) else {}
        vprint(verbose, f"Geladene bukkit.yml Typstruktur: {type(loaded).__name__}")
        worlds = bukkit_cfg.get("worlds")
        if not isinstance(worlds, dict):
            worlds = {}
            bukkit_cfg["worlds"] = worlds

        world_entry = worlds.get(cfg.name)
        if not isinstance(world_entry, dict):
            world_entry = {}
            worlds[cfg.name] = world_entry

        existing_generator = str(world_entry.get("generator", "")).strip()
        vprint(verbose, f"Vorhandener Generator für '{cfg.name}': {existing_generator or '<leer>'}")
        if existing_generator and existing_generator.lower() != "homeclaim":
            overwrite = prompt_yes_no(
                f"Generator für Welt '{cfg.name}' ist bereits '{existing_generator}'. Überschreiben auf 'HomeClaim'?",
                default_yes=False,
                auto_yes=auto_yes,
            )
            if not overwrite:
                print("ℹ️  bukkit.yml-Merge übersprungen (bestehender Generator bleibt erhalten).")
                return True

        world_entry["generator"] = "HomeClaim"

        ts = datetime.now().strftime("%Y%m%d-%H%M%S")
        backup = server_root / f"bukkit.yml.bak.{ts}"
        backup.write_text(bukkit_yml.read_text(encoding="utf-8"), encoding="utf-8")
        print(f"✓ Backup erstellt: {backup}")

        bukkit_yml.write_text(yaml.dump(bukkit_cfg, default_flow_style=False, sort_keys=False), encoding="utf-8")
        print(f"✓ bukkit.yml gemerged: {bukkit_yml}")
        print(f"  Eintrag: worlds.{cfg.name}.generator = HomeClaim")
        return True
    except Exception as e:
        print(f"❌ Fehler bei --apply: {e}")
        return False


def parse_simple_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def resolve_runtime_paths(script_file: Path, cwd: Path, data_arg: str | None, worlddata_arg: str | None, verbose: bool = False) -> tuple[Path, Path]:
    """
    Resolve HomeClaim data dir and worlddata output dir.

    Allowed without explicit args:
      - server root (contains server.properties + bukkit.yml)
      - plugins/HomeClaim/scripts
    """
    if data_arg or worlddata_arg:
        data_dir = Path(data_arg).resolve() if data_arg else (cwd / "plugins" / "HomeClaim").resolve()
        worlddata_dir = Path(worlddata_arg).resolve() if worlddata_arg else (cwd / "generate-world.py-data").resolve()
        vprint(verbose, f"Explizite Pfade: data={data_dir}, worlddata={worlddata_dir}")
        return data_dir, worlddata_dir

    is_server_root = (cwd / "server.properties").exists() and (cwd / "bukkit.yml").exists()
    if is_server_root:
        vprint(verbose, f"Auto-Pfade aus Server-Root: data={cwd / 'plugins' / 'HomeClaim'}, worlddata={cwd / 'generate-world.py-data'}")
        return (cwd / "plugins" / "HomeClaim").resolve(), (cwd / "generate-world.py-data").resolve()

    script_dir = script_file.resolve().parent
    if cwd.resolve() == script_dir and cwd.parts[-3:] == ("plugins", "HomeClaim", "scripts"):
        vprint(verbose, f"Auto-Pfade aus scripts-Verzeichnis: data={cwd.parent}, worlddata={cwd / 'generate-world.py-data'}")
        return cwd.parent.resolve(), (cwd / "generate-world.py-data").resolve()

    print("❌ Fehler: Unbekanntes Ausführungsverzeichnis.")
    print("   Erlaubt ohne Extra-Parameter nur:")
    print("   - Server-Root (mit server.properties + bukkit.yml)")
    print("   - plugins/HomeClaim/scripts")
    print("   Alternativ explizit setzen: --data <HomeClaim-Datenordner> --worlddata <Ausgabeordner>")
    raise SystemExit(2)


def infer_default_world_name(data_dir: Path, server_root: Path | None) -> str | None:
    """
    Try to infer a sensible world name:
      1) Existing HomeClaim generator entry from bukkit.yml
      2) level-name from server.properties + '_plots'
    """
    if server_root:
        bukkit_yml = server_root / "bukkit.yml"
        if bukkit_yml.exists():
            try:
                bukkit_cfg = yaml.safe_load(bukkit_yml.read_text(encoding="utf-8")) or {}
                worlds = bukkit_cfg.get("worlds", {}) if isinstance(bukkit_cfg, dict) else {}
                if isinstance(worlds, dict):
                    for world_name, world_cfg in worlds.items():
                        if isinstance(world_cfg, dict):
                            generator = str(world_cfg.get("generator", "")).strip().lower()
                            if generator == "homeclaim":
                                return str(world_name)
            except Exception:
                pass

        props = parse_simple_properties(server_root / "server.properties")
        level_name = props.get("level-name")
        if level_name:
            return f"{level_name}_plots"

    existing_plot_worlds = data_dir / "plot-worlds"
    if existing_plot_worlds.exists():
        for toml_file in sorted(existing_plot_worlds.glob("*.toml")):
            return toml_file.stem
    return None


def detect_server_settings(server_root: Path | None, verbose: bool = False) -> dict[str, str]:
    settings: dict[str, str] = {}
    if not server_root:
        vprint(verbose, "Keine Server-Settings erkannt, da kein Server-Root vorliegt.")
        return settings

    bukkit_yml = server_root / "bukkit.yml"
    if bukkit_yml.exists():
        try:
            bukkit_cfg = yaml.safe_load(bukkit_yml.read_text(encoding="utf-8")) or {}
            worlds = bukkit_cfg.get("worlds", {}) if isinstance(bukkit_cfg, dict) else {}
            if isinstance(worlds, dict):
                for world_name, world_cfg in worlds.items():
                    if isinstance(world_cfg, dict):
                        generator = str(world_cfg.get("generator", "")).strip().lower()
                        if generator == "homeclaim":
                            settings["bukkit_homeclaim_world"] = str(world_name)
                            vprint(verbose, f"Erkannte HomeClaim-Welt in bukkit.yml: {world_name}")
                            break
        except Exception:
            pass

    props = parse_simple_properties(server_root / "server.properties")
    level_name = props.get("level-name")
    if level_name:
        settings["server_level_name"] = level_name
        settings["suggested_world_name"] = f"{level_name}_plots"
        vprint(verbose, f"Erkannter level-name: {level_name}")

    if "bukkit_homeclaim_world" in settings:
        settings["suggested_world_name"] = settings["bukkit_homeclaim_world"]
    vprint(verbose, f"Settings-Detection Ergebnis: {settings if settings else '<leer>'}")

    return settings


def prompt_use_detected(settings: dict[str, str], auto_yes: bool) -> bool:
    if auto_yes:
        return True
    if not sys.stdin.isatty():
        return False

    print("\n🔎 Server-Einstellungen erkannt:")
    if settings.get("server_level_name"):
        print(f"  - server.properties level-name: {settings['server_level_name']}")
    if settings.get("bukkit_homeclaim_world"):
        print(f"  - bukkit.yml HomeClaim-Welt: {settings['bukkit_homeclaim_world']}")
    if settings.get("suggested_world_name"):
        print(f"  - Vorgeschlagener Weltname: {settings['suggested_world_name']}")

    while True:
        answer = input("Übernehmen? [Y/n]: ").strip().lower()
        if answer in ("", "y", "yes", "j", "ja"):
            return True
        if answer in ("n", "no", "nein"):
            return False
        print("Bitte 'y' oder 'n' eingeben.")


def preview_apply_changes(cfg: WorldConfig, data_dir: Path, server_root: Path, verbose: bool = False) -> bool:
    """
    Preview which files/keys would be changed by --apply without writing anything.
    """
    try:
        plot_worlds_dir = data_dir / "plot-worlds"
        toml_target = plot_worlds_dir / f"{cfg.name}.toml"
        bukkit_yml = server_root / "bukkit.yml"

        print("\n🔎 APPLY-DRY-RUN (keine Dateien werden geschrieben)")
        print(f"  TOML-Ziel: {toml_target}")
        print(f"  Bukkit-Ziel: {bukkit_yml}")

        if not bukkit_yml.exists():
            print(f"❌ bukkit.yml nicht gefunden: {bukkit_yml}")
            return False

        loaded = yaml.safe_load(bukkit_yml.read_text(encoding="utf-8"))
        bukkit_cfg = loaded if isinstance(loaded, dict) else {}
        worlds = bukkit_cfg.get("worlds")
        if not isinstance(worlds, dict):
            worlds = {}

        world_entry = worlds.get(cfg.name)
        current_generator = ""
        if isinstance(world_entry, dict):
            current_generator = str(world_entry.get("generator", "")).strip()

        print(f"  Aktueller worlds.{cfg.name}.generator: {current_generator or '<nicht gesetzt>'}")
        print(f"  Neuer worlds.{cfg.name}.generator: HomeClaim")

        if verbose:
            print("\n[verbose] TOML-Vorschau:")
            print(render_homeclaim_toml(cfg).strip())

        print("\nÄnderungen (Preview):")
        if current_generator.lower() == "homeclaim":
            print(f"  - worlds.{cfg.name}.generator bleibt 'HomeClaim' (idempotent)")
        elif current_generator:
            print(f"  - worlds.{cfg.name}.generator: '{current_generator}' -> 'HomeClaim'")
        else:
            print(f"  - worlds.{cfg.name}.generator: <nicht gesetzt> -> 'HomeClaim'")
        print(f"  - {toml_target} wird erstellt/überschrieben")
        print("  - bukkit.yml Backup würde erstellt werden (bei echtem --apply)")
        return True
    except Exception as e:
        print(f"❌ Fehler bei --apply-dry-run: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(
        description="HomeClaim World Generation Script",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Beispiele:
  # Einfaches Setup
  ./generate-world.py --name "MyWorld"
  
  # Große Welt
    ./generate-world.py --name "MegaWorld" --plots-per-side 1000 --size 64
  
  # Aus JSON-Datei laden
  ./generate-world.py -c world-config.json
        """
    )
    
    parser.add_argument("-n", "--name", help="Welt-Name")
    parser.add_argument("-s", "--size", type=int, default=48, help="Plot-Größe (default: 48)")
    parser.add_argument("-r", "--road-width", type=int, default=10, help="Straßen-Breite (default: 10)")
    parser.add_argument("-e", "--height", type=int, default=64, help="Plot-Höhe (default: 64)")
    parser.add_argument("-p", "--plot-block", default="GRASS_BLOCK", help="Plot-Block")
    parser.add_argument("-b", "--road-block", default="DARK_PRISMARINE", help="Straßen-Block")
    parser.add_argument("-w", "--wall-block", default="DIAMOND_BLOCK", help="Mauer-Block")
    parser.add_argument("-a", "--accent-block", default="SMOOTH_QUARTZ_STAIRS", help="Akzent-Block")
    parser.add_argument("--plots-per-side", type=int, default=500, help="Plots/Seite (default: 500)")
    parser.add_argument("-c", "--config", help="Config von JSON-Datei laden")
    parser.add_argument("--output-dir", default=None, help="Alias für --worlddata (abwärtskompatibel)")
    parser.add_argument("--data", default=None, help="Pfad zu plugins/HomeClaim (Data-Ordner)")
    parser.add_argument("--worlddata", default=None, help="Ausgabe-Ordner für Bundles (default: ./generate-world.py-data)")
    parser.add_argument("-y", "--yes", action="store_true", help="Erkannte Server-Einstellungen ohne Nachfrage übernehmen")
    parser.add_argument("--use-defaults", "--defaults", action="store_true", help="Übernimmt explizit die HomeClaim-Standardwerte und schreibt sie in die erzeugte Struktur")
    parser.add_argument("--apply", action="store_true", help="Schreibt TOML direkt nach plugins/HomeClaim/plot-worlds und merged bukkit.yml")
    parser.add_argument("--apply-dry-run", action="store_true", help="Zeigt exakt, was --apply in bukkit.yml ändern würde (ohne Schreibzugriff)")
    parser.add_argument("--verbose", action="store_true", help="Ausführliche Ausgabe")
    parser.add_argument("--config-only", action="store_true", help="Kompatibilitätsflag (hat keinen Effekt, CLI-only ist Standard)")
    parser.add_argument("--dry-run", action="store_true", help="Nur anzeigen, nicht generieren")
    
    args = parser.parse_args()

    if args.apply and args.apply_dry_run:
        print("❌ --apply und --apply-dry-run schließen sich gegenseitig aus.")
        return 2

    script_file = Path(__file__).resolve()
    cwd = Path.cwd().resolve()
    worlddata_arg = args.worlddata or args.output_dir
    data_dir, worlddata_dir = resolve_runtime_paths(script_file, cwd, args.data, worlddata_arg, verbose=args.verbose)

    server_root = detect_server_root(cwd, verbose=args.verbose)
    inferred_name = infer_default_world_name(data_dir, server_root)
    detected_settings = detect_server_settings(server_root, verbose=args.verbose)
    
    # Lade Konfiguration
    if args.config:
        try:
            with open(args.config) as f:
                config_dict = json.load(f)
            config = WorldConfig(**config_dict)
        except Exception as e:
            print(f"❌ Fehler beim Laden der Konfiguration: {e}")
            sys.exit(1)
    else:
        name = args.name
        if not name and detected_settings:
            if prompt_use_detected(detected_settings, args.yes):
                name = detected_settings.get("suggested_world_name")
                if name:
                    print(f"✓ Übernommen: Welt-Name = {name}")
            else:
                print("ℹ️  Übernahme abgelehnt, nutze explizite CLI-Werte.")

        if not name:
            name = inferred_name

        if not name:
            print("❌ Fehler: Welt-Name erforderlich (-n/--name) oder Config-Datei (-c)")
            print("   Tipp: Führe das Script im Server-Root aus oder setze --name explizit.")
            parser.print_help()
            sys.exit(1)
        
        config = WorldConfig(
            name=name,
            plot_size=args.size,
            road_width=args.road_width,
            plot_height=args.height,
            plot_block=args.plot_block,
            road_block=args.road_block,
            wall_block=args.wall_block,
            accent_block=args.accent_block,
            plots_per_side=args.plots_per_side
        )

    if args.use_defaults:
        config = WorldConfig(name=config.name)
        print("✓ Standardwerte aktiviert: Plot-/Block-Defaults werden übernommen und ins Bundle geschrieben.")
    
    # Validiere
    if not config.validate():
        sys.exit(1)
    
    # Zeige Konfiguration
    config.display()
    print(f"Laufzeitpfade:")
    print(f"  HomeClaim-Daten: {data_dir}")
    print(f"  Ausgabe (worlddata): {worlddata_dir}")
    
    if args.dry_run:
        print("🔍 DRY-RUN: Keine Generierung durchgeführt")
        return 0

    if args.apply_dry_run:
        target_server_root = ensure_apply_allowed(server_root, args.yes)
        return 0 if preview_apply_changes(config, data_dir, target_server_root, verbose=args.verbose) else 1
    
    # Generiere
    gen = WorldGenerator(
        config=config,
    )
    
    # Speichere Welt-Bundle (nur Weltdaten/Snippets)
    if not gen.create_world_bundle(str(worlddata_dir), data_dir, include_defaults=args.use_defaults):
        return 1

    if args.apply:
        target_server_root = ensure_apply_allowed(server_root, args.yes)
        if not apply_to_server(config, data_dir, target_server_root, args.yes, verbose=args.verbose):
            return 1

    print("\n✅ Welt-Generierung erfolgreich abgeschlossen (CLI-only)")
    print(f"   Welt: {config.name}")
    print(f"   Plots: {config.total_plots}")
    if args.config_only:
        print("   Hinweis: --config-only ist nur aus Kompatibilitätsgründen vorhanden.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
