{
  description = "oml JVM development and production package";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      perSystem =
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          jdk = pkgs.jdk21_headless;
          clojureCli = pkgs.clojure.override { inherit jdk; };
          clojureJar = pkgs.fetchurl {
            url = "https://repo.maven.apache.org/maven2/org/clojure/clojure/1.12.0/clojure-1.12.0.jar";
            hash = "sha256-xFMzAGRBoFnqn9sTQfxsH0C5IaENzNgmZTEeSKA4R2M=";
          };
          specAlphaJar = pkgs.fetchurl {
            url = "https://repo.maven.apache.org/maven2/org/clojure/spec.alpha/0.5.238/spec.alpha-0.5.238.jar";
            hash = "sha256-lM2ZtupjlkHzevSGCmQ7btOZ7lqL5dcXz/C2Y8jXUHc=";
          };
          coreSpecsAlphaJar = pkgs.fetchurl {
            url = "https://repo.maven.apache.org/maven2/org/clojure/core.specs.alpha/0.4.74/core.specs.alpha-0.4.74.jar";
            hash = "sha256-63OsCM9JuoQMiLpnvu8RM2ylVDM9lAiAjXiUbg/rnds=";
          };
          runtimeClasspath = pkgs.lib.concatStringsSep ":" [
            clojureJar
            specAlphaJar
            coreSpecsAlphaJar
          ];
          oml = pkgs.stdenvNoCC.mkDerivation {
            pname = "oml";
            version = "0.1.0";
            src = ./src;
            dontBuild = true;
            installPhase = ''
              runHook preInstall

              mkdir -p "$out/bin" "$out/share/oml/src"
              cp -R . "$out/share/oml/src"
              cat > "$out/bin/oml" <<EOF
              #!${pkgs.runtimeShell}
              exec ${jdk}/bin/java -cp "${runtimeClasspath}:$out/share/oml/src" clojure.main -m oml.core "\$@"
              EOF
              chmod +x "$out/bin/oml"

              runHook postInstall
            '';
            meta = {
              description = "A Lisp image you run and inhabit";
              mainProgram = "oml";
              platforms = systems;
            };
          };
          testRunner = pkgs.writeText "oml-test-runner.clj" ''
            (require 'clojure.test)
            (require 'oml.core-test 'oml.kernel-test 'oml.repl-test)
            (let [{:keys [fail error]}
                  (clojure.test/run-tests
                    'oml.core-test
                    'oml.kernel-test
                    'oml.repl-test)]
              (System/exit (if (zero? (+ fail error)) 0 1)))
          '';
          jvmTests = pkgs.runCommand "oml-jvm-tests" { } ''
            ${jdk}/bin/java \
              -cp "${runtimeClasspath}:${./src}:${./test}" \
              clojure.main "${testRunner}"
            touch "$out"
          '';
          devShell = pkgs.mkShell {
            packages = [
              jdk
              clojureCli
            ];
          };
          productionCheck = pkgs.runCommand "oml-production-check" { } ''
            actual="$(printf '(+ 1 2)\n' | ${oml}/bin/oml)"
            expected='oml> 3
            oml> '
            if [ "$actual" != "$expected" ]; then
              printf 'unexpected oml output:\n%s\n' "$actual" >&2
              exit 1
            fi
            touch "$out"
          '';
          site = pkgs.buildNpmPackage {
            pname = "oml-site";
            version = "0.1.0";
            src = ./site;
            npmDepsHash = "sha256-w5lFWRkLz/Sk/e4Jm4t4I+oq6wKQpBYWe8jxOb0apTw=";
            buildPhase = ''
              npm run build
            '';
            installPhase = ''
              mkdir -p $out
              cp -R _site $out/
            '';
            meta.description = "Generated oml documentation site";
          };
        in
        {
          inherit
            devShell
            clojureCli
            jdk
            jvmTests
            oml
            productionCheck
            site
            ;
        };
    in
    {
      packages = forAllSystems (system: {
        default = (perSystem system).oml;
        oml = (perSystem system).oml;
      });

      apps = forAllSystems (system: {
        default = {
          type = "app";
          program = "${(perSystem system).oml}/bin/oml";
        };
      });

      devShells = forAllSystems (system: {
        default = (perSystem system).devShell;
      });

      checks = forAllSystems (system: {
        default = (perSystem system).productionCheck;
        jvm-tests = (perSystem system).jvmTests;
        package = (perSystem system).oml;
        site = (perSystem system).site;
      });
    };
}
