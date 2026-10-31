GROUP="Languages"; SOURCE="termux pkg"; PKGS="php"; BINS="php"
install() { apt_add php; }
probe() {
  printf '<?php echo json_encode(["sum" => array_sum(range(1,10))]), "\\n";\n' >a.php
  php a.php | need '"sum":55'
}
