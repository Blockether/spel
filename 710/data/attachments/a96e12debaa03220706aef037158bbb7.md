## DELETE http://localhost:33225/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 17 Aug 2026 19:38:46 GMT
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
curl 'http://localhost:33225/echo' \
  -X DELETE
```
