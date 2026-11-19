## DELETE http://localhost:38583/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Thu, 19 Nov 2026 12:49:08 GMT
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
curl 'http://localhost:38583/echo' \
  -X DELETE
```
