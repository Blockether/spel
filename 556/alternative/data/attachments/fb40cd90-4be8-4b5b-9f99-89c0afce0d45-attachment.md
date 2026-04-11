## DELETE http://localhost:46363/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 11 Apr 2026 12:15:04 GMT
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
curl 'http://localhost:46363/echo' \
  -X DELETE
```
