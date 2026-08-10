## DELETE http://localhost:37495/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 10 Aug 2026 12:19:50 GMT
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
curl 'http://localhost:37495/echo' \
  -X DELETE
```
