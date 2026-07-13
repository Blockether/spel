## DELETE http://localhost:34275/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 13 Jul 2026 11:37:43 GMT
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
curl 'http://localhost:34275/echo' \
  -X DELETE
```
