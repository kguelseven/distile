# Homebrew formula for distile.
#
# This lives in the distile repo itself rather than a separate homebrew-distile tap,
# so releasing needs no cross-repo token: .github/workflows/release.yml rewrites the
# url and sha256 below using the built-in GITHUB_TOKEN. The cost is that users tap
# with an explicit URL once (see README).
#
# Homebrew always reads this from the default branch, never from a tag, so the copy
# on a tagged commit intentionally lags one release behind.
#
# Directory must be Formula/, not the also-valid HomebrewFormula/: Homebrew's rubocop
# exempts "**/{Formula,Casks}/**/*.rb" from Style/FrozenStringLiteralComment, so the
# other name fails `brew audit --strict`.
class Distile < Formula
  desc "Streaming log template extractor (Drain algorithm) - local, offline, dev-time"
  homepage "https://github.com/kguelseven/distile"
  url "https://github.com/kguelseven/distile/releases/download/v0.1.0/distile-0.1.0.tar.gz"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license "MIT"

  # distile targets Java 21 (maven.compiler.release), but runs on anything newer.
  # Homebrew has no version-constraint syntax for formula dependencies, so ">= 21" is
  # the unversioned openjdk (always the latest) plus the "21+" lower bound below, which
  # lets java_home accept any already-installed JDK 21 or newer.
  depends_on "openjdk"

  def install
    libexec.install "distile.jar"

    # Deliberately not installing the repo's ./distile launcher: it calls a bare `java`,
    # which a Homebrew user may not have on PATH. write_jar_script generates a wrapper
    # that resolves JAVA_HOME to a matching openjdk keg (overridable at runtime). The
    # java_opts argument carries the flag that silences JDK warnings from JLine's native
    # terminal provider (the --top view).
    bin.write_jar_script libexec/"distile.jar", "distile",
                         "--enable-native-access=ALL-UNNAMED", java_version: "21+"
  end

  test do
    # Closes the version loop: -Drevision -> jar manifest -> Main.ManifestVersion.
    # A mis-injected revision fails here instead of shipping silently.
    assert_match "distile #{version}", shell_output("#{bin}/distile --version")

    (testpath/"app.log").write <<~LOG
      User 1 logged in from 10.0.0.1
      User 2 logged in from 10.0.0.2
      User 3 logged in from 10.0.0.3
    LOG

    # Three lines differing only in their variable parts must collapse to one template.
    output = shell_output("#{bin}/distile #{testpath}/app.log")
    assert_match "User <*> logged in from <*>", output
  end
end
