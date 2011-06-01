$(document).ready(function() {
    // TODO: handle errors that are larger than a single token
    var findError = function(token, errors) {
            for (var i = 0; i < errors.length; i += 1) {
                if (errors[i].lineNumber === token.lineNumber && errors[i].characterNumber === token.characterNumber) {
                    return errors[i].description;
                }
            }
            return null;
        },
        displayErrors = function(errors) {
            var errorsBody = $("#errors tbody").empty();
            $.each(errors, function(index, error) {
                var row = $(document.createElement("tr"));
                row.append($(document.createElement("td")).text(error.description));
                row.append($(document.createElement("td")).text("Line " + error.lineNumber + ", char " + error.characterNumber));
                errorsBody.append(row);
            });
        },
        displayHighlightedSource = function(tokens, errors) {
            var sourceElement = $("#annotated-source").empty();
            $.each(tokens, function(index, token) {
                var error = findError(token, errors),
                    element = $(document.createElement("span"))
                        .text(token.value)
                        .addClass("token-" + token.type);
                if (error) {
                    element.addClass("token-error");
                }
                if (token.type === "end" && error) {
                    element.text(" ");
                }
                sourceElement.append(element);
            });
        };
    $("#source input").click(function() {
        $.ajax({
            url: "/compile",
            dataType: "json",
            data: $("#source textarea").val(),
            type: "POST",
            success: function(response) {
                displayHighlightedSource(response.tokens, response.errors);
                displayErrors(response.errors);
            }
        });
    });
});
