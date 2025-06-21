`use strict`
(function() {
    const countdownDiv = document.getElementById("countdown");

    function startCountdown() {
        const source = new EventSource("/start");

        source.onMessage = function({data}) {
            const newDiv = document.createElement("div");
            newDiv.textContent = data
            countdownDiv.appendChild(newDiv);
        }
    }

    document.getElementById("start-button").addEventListener("click", startCountdown);
})();
