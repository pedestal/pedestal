(function() {
    const countdownDiv = document.getElementById("countdown");
    const startButton = document.getElementById("start-button");
    let source = null;

    function startCountdown() {
        if (source) source.close();
        countdownDiv.innerHTML = "";
        startButton.disabled = true;

        source = new EventSource("/start?count=10");

        source.onmessage = function({data}) {
            const newDiv = document.createElement("div");
            newDiv.textContent = data;
            countdownDiv.appendChild(newDiv);

            if (data === "Blastoff!") {
                source.close();
                startButton.disabled = false;
            }
        };

        source.onerror = function() {
            source.close();
            source = null;
            startButton.disabled = false;
        };
    }

    startButton.addEventListener("click", startCountdown);
})();
