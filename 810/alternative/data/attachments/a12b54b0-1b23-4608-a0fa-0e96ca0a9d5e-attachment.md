## DELETE http://localhost:38119/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 02 Nov 2026 18:55:32 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:38119/echo' \
  -X DELETE
```
