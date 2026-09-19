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
          dataJsonJar = pkgs.fetchurl {
            url = "https://repo.maven.apache.org/maven2/org/clojure/data.json/2.5.1/data.json-2.5.1.jar";
            hash = "sha256-baVzUeM0FzyF3qFpbH1IR6Tz4R266zl9pl56JoSelLM=";
          };
          sciJar = pkgs.fetchurl {
            url = "https://repo.clojars.org/org/babashka/sci/0.15.58/sci-0.15.58.jar";
            hash = "sha256-rqDhY9lxFzPhrZSB3Kd+ThYYaCASMWrFgW1IBYbdGMo=";
          };
          edamameJar = pkgs.fetchurl {
            url = "https://repo.clojars.org/borkdude/edamame/1.6.42/edamame-1.6.42.jar";
            hash = "sha256-OrL72ww2BrDcSnDItafjxXkPqukvCYTxowO9myMp9t0=";
          };
          sciTypesJar = pkgs.fetchurl {
            url = "https://repo.clojars.org/org/babashka/sci.impl.types/0.0.3/sci.impl.types-0.0.3.jar";
            hash = "sha256-0zHboOBzjDgdc38YUlBw51tj8/Z+/IjTRQ2wDHVf5t4=";
          };
          graalLockingJar = pkgs.fetchurl {
            url = "https://repo.clojars.org/borkdude/graal.locking/0.0.2/graal.locking-0.0.2.jar";
            hash = "sha256-eFlpdVXBVNcgMM6zRTTPuqcKxLWJhAQdN4ULU733FF4=";
          };
          toolsReaderJar = pkgs.fetchurl {
            url = "https://repo.maven.apache.org/maven2/org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar";
            hash = "sha256-y5btDv3wuLw2JjLLdAZ+8iX2sD8/R4yLpEGC9qF6N7Y=";
          };
          runtimeClasspath = pkgs.lib.concatStringsSep ":" [
            clojureJar
            specAlphaJar
            coreSpecsAlphaJar
            dataJsonJar
            sciJar
            edamameJar
            sciTypesJar
            graalLockingJar
            toolsReaderJar
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
            (require
              'oml.agent-test
              'oml.core-test
              'oml.grant-test
              'oml.kernel-test
              'oml.repl-test)
            (let [{:keys [fail error]}
                  (clojure.test/run-tests
                    'oml.agent-test
                    'oml.core-test
                    'oml.grant-test
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
        in
        {
          inherit
            devShell
            clojureCli
            jdk
            jvmTests
            oml
            productionCheck
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
      });
    };
}
