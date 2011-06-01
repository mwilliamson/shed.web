$(document).ready(function() {
    var displayErrors = function(errors) {
            var errorsBody = $("#errors tbody").empty();
            $.each(errors, function(index, error) {
                var row = $(document.createElement("tr"));
                row.append($(document.createElement("td")).text(error.description));
                row.append($(document.createElement("td")).text("Line " + error.lineNumber + ", char " + error.characterNumber));
                errorsBody.append(row);
            });
        },
        displayHighlightedSource = function(tokens) {
            var element = $("#annotated-source").empty();
            $.each(tokens, function(index, token) {
                element.append(
                    $(document.createElement("span"))
                        .text(token.value)
                        .addClass("token-" + token.type)
                );
            });
        };
    $("#source input").click(function() {
        $.ajax({
            url: "/compile",
            dataType: "json",
            data: $("#source textarea").val(),
            type: "POST",
            success: function(response) {
                displayHighlightedSource(response.tokens);
                displayErrors(response.errors);
            }
        });
    });
});
