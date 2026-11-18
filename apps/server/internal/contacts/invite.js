(function () {
  var codePattern = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{10}$/;
  var rawHash = window.location.hash ? window.location.hash.slice(1).trim().toUpperCase() : "";
  var codeSection = document.getElementById("code-section");
  var missingSection = document.getElementById("missing-section");
  var codeText = document.getElementById("invite-code-text");
  var openAppBtn = document.getElementById("open-app-btn");
  var copyBtn = document.getElementById("copy-code-btn");
  var copyStatus = document.getElementById("copy-status");

  if (!codePattern.test(rawHash)) {
    return;
  }

  codeText.textContent = rawHash;
  var customSchemeUrl = "anve://invite/" + rawHash;
  var isAndroid = /Android/i.test(navigator.userAgent || "");
  if (isAndroid) {
    openAppBtn.setAttribute(
      "href",
      "intent://invite/" +
        rawHash +
        "#Intent;scheme=anve;package=com.anve.app;S.browser_fallback_url=" +
        encodeURIComponent(window.location.href) +
        ";end"
    );
  } else {
    openAppBtn.setAttribute("href", customSchemeUrl);
  }
  codeSection.classList.remove("hidden");
  openAppBtn.classList.remove("hidden");
  missingSection.classList.add("hidden");

  copyBtn.addEventListener("click", function () {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(rawHash).then(function () {
        copyStatus.textContent = "Đã sao chép mã mời.";
      }, function () {
        copyStatus.textContent = "Không tự sao chép được. Bạn hãy chọn và sao chép mã ở trên.";
      });
    } else {
      copyStatus.textContent = "Trình duyệt không hỗ trợ sao chép tự động. Bạn hãy sao chép mã ở trên.";
    }
  });
})();
