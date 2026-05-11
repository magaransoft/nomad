{
  description = "Dev environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forEachSystem = f: builtins.listToAttrs (map (system: {
        name = system;
        value = f system;
      }) supportedSystems);
    in {
      devShells = forEachSystem (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          # Tarball of pkgs.postgresql in the layout zonky's embedded-postgres expects
          # (bin/, lib/, share/ at root). Used by the postgres scripted test on NixOS,
          # where the bundled generic-Linux binaries can't run.
          pgTarball = pkgs.runCommand "nomad-zonky-postgres.txz" {
            nativeBuildInputs = [ pkgs.gnutar pkgs.xz ];
          } ''
            tar -cJf $out -C ${pkgs.postgresql} bin lib share
          '';
        in {
          default = pkgs.mkShell {
            buildInputs = with pkgs; [ jdk21 sbt ];
            shellHook = ''
              export JAVA_HOME="${pkgs.jdk21}"
              export NOMAD_PG_TARBALL="${pgTarball}"
            '';
          };
        }
      );
    };
}
