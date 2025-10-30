module se.mau.chifferchat {
    requires javafx.controls;
    requires javafx.fxml;


    opens se.mau.chifferchat to javafx.fxml;
    exports se.mau.chifferchat;
}