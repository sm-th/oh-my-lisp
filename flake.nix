{
  description = "oml — oh my lisp";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        oml = pkgs.writeShellApplication {
          name = "oml";
          runtimeInputs = [ pkgs.babashka ];
          text = ''exec bb --classpath ${./src} -m oml.main "$@"'';
        };
      in {
        packages.default = oml;
        packages.oml = oml;
        apps.default = { type = "app"; program = "${oml}/bin/oml"; };
        devShells.default = pkgs.mkShell { packages = [ pkgs.babashka ]; };
      });
}
