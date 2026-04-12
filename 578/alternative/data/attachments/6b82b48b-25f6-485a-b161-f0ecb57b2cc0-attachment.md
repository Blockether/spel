## DELETE http://localhost:41095/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 12 Apr 2026 08:34:06 GMT
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
curl 'http://localhost:41095/echo' \
  -X DELETE
```
