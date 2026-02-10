import { NativeMic } from 'native-mic-capacitor';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    NativeMic.echo({ value: inputValue })
}
