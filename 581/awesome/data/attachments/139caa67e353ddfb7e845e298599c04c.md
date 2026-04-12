## DELETE http://localhost:42583/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 12 Apr 2026 09:37:30 GMT
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
curl 'http://localhost:42583/echo' \
  -X DELETE
```
