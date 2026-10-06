{ pkgs, ... }:

let
  toadVersion = "0.6.20";
in
{
  packages = [ pkgs.babashka ];

  # Toad (PyPI: batrachian-toad) is not in nixpkgs and needs Python 3.14.
  # It is installed with uv into the devenv venv (.devenv/state/venv) on
  # shell entry, pinned to toadVersion.
  languages.python = {
    enable = true;
    package = pkgs.python314;
    venv.enable = true;
    venv.requirements = "batrachian-toad==${toadVersion}";
    uv.enable = true;
  };

  # Optional, gitignored .env (see .env.example) for OML_MODEL, keys, ...
  dotenv.enable = true;

  scripts = {
    oml-test.exec = ''cd "$DEVENV_ROOT" && bb test'';
    oml-prompt.exec = ''bb --config "$DEVENV_ROOT/bb.edn" prompt "$@"'';
    oml-acp.exec = ''exec "$DEVENV_ROOT/bin/oml-acp" "$@"'';
    oml-toad.exec = ''exec toad acp "$DEVENV_ROOT/bin/oml-acp" "''${1:-$PWD}"'';
  };

  enterShell = ''
    echo "oml devenv: bb $(bb --version | cut -d' ' -f2), $(python --version), toad ${toadVersion}" >&2
    echo "scripts: oml-test, oml-prompt \"text\", oml-acp, oml-toad [project-dir]" >&2
  '';
}
