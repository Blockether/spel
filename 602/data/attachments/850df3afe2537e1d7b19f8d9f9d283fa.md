## DELETE http://localhost:33917/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 13 Apr 2026 12:46:33 GMT
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
curl 'http://localhost:33917/echo' \
  -X DELETE
```
