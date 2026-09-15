## DELETE http://localhost:41215/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 15 Sep 2026 10:30:45 GMT
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
curl 'http://localhost:41215/echo' \
  -X DELETE
```
