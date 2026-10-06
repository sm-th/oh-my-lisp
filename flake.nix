{
  description = "oml - minimal coding-agent harness in babashka";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs:
        let
          python = pkgs.python314;
          # Toad (PyPI: batrachian-toad) is not in nixpkgs. Run the pinned
          # version through uvx on the nix Python; uv caches it after the
          # first run.
          toad = pkgs.writeShellScriptBin "toad" ''
            export UV_PYTHON_DOWNLOADS=never
            exec ${pkgs.uv}/bin/uvx --python ${python}/bin/python3 \
              --from batrachian-toad==0.6.20 toad "$@"
          '';
          script = name: body: pkgs.writeShellScriptBin name ''
            : "''${OML_HOME:?run inside the oml dev shell}"
            ${body}
          '';
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.babashka
              python
              pkgs.uv
              toad
              (script "oml-test" ''cd "$OML_HOME" && exec bb test'')
              (script "oml-prompt" ''exec bb --config "$OML_HOME/bb.edn" prompt "$@"'')
              (script "oml-acp" ''exec "$OML_HOME/bin/oml-acp" "$@"'')
              (script "oml-toad" ''exec toad acp "$OML_HOME/bin/oml-acp" "''${1:-$PWD}"'')
            ];
            shellHook = ''
              export OML_HOME="$PWD"
              if [ -f .env ]; then set -a; . ./.env; set +a; fi
            '';
          };
        });
    };
}
